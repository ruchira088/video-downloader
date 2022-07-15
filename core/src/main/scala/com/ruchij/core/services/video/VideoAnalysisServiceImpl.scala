package com.ruchij.core.services.video

import cats.data.Kleisli
import cats.effect.{Async, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, ~>}
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.CustomVideoSite.Selector
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite, WebPage}
import com.ruchij.core.exceptions.{ExternalServiceException, ValidationException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.renderer.SpaSiteRenderer
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated, VideoMetadataResult}
import com.ruchij.core.services.video.models.VideoAnalysisResult
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.core.types.JodaClock
import com.ruchij.core.utils.Http4sUtils
import org.http4s.Method.{GET, HEAD}
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.`Content-Length`
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class VideoAnalysisServiceImpl[F[_]: Async: JodaClock, T[_]: Monad](
  hashingService: HashingService[F],
  downloadService: DownloadService[F],
  youTubeVideoDownloader: YouTubeVideoDownloader[F],
  client: Client[F],
  spaSiteRenderer: SpaSiteRenderer[F],
  cliCommandRunner: CliCommandRunner[F],
  videoMetadataDao: VideoMetadataDao[T],
  fileResourceDao: FileResourceDao[T],
  storageConfiguration: StorageConfiguration
)(implicit transaction: T ~> F)
    extends VideoAnalysisService[F] {

  private val logger = Logger[VideoAnalysisServiceImpl[F, T]]

  private val clientDsl = Http4sClientDsl[F]
  import clientDsl._

  override def metadata(uri: Uri): F[VideoMetadataResult] =
    for {
      videoMetadataOpt <- transaction(videoMetadataDao.findByUrl(uri))

      result <- videoMetadataOpt.fold[F[VideoMetadataResult]](createMetadata(uri).map(NewlyCreated)) { videoMetadata =>
        Applicative[F].pure[VideoMetadataResult](Existing(videoMetadata))
      }

    } yield result

  def createMetadata(uri: Uri): F[VideoMetadata] =
    for {
      videoAnalysisResult @ VideoAnalysisResult(processedUri, videoSite, title, duration, size, thumbnailUri) <- analyze(uri)
      _ <- logger.info[F](s"Uri=${processedUri.renderString} Result=$videoAnalysisResult")

      videoId <-
        hashingService.hash(processedUri.renderString)
          .product(hashingService.hash(title))
          .map { case (urlHash, titleHash) => s"${videoSite.name.toLowerCase}-$urlHash$titleHash" }

      timestamp <- JodaClock[F].timestamp

      thumbnailFileName = thumbnailUri.path.segments.lastOption.map(_.encoded).getOrElse("thumbnail.unknown")
      filePath = s"${storageConfiguration.imageFolder}/thumbnail-$videoId-$thumbnailFileName"

      thumbnail <- downloadService
        .download(thumbnailUri, filePath)
        .use { downloadResult =>
          downloadResult.data.compile.drain
            .productR(hashingService.hash(thumbnailUri.renderString).map(hash => s"$videoId-$hash"))
            .map { fileId =>
              FileResource(
                fileId,
                timestamp,
                downloadResult.downloadedFileKey,
                downloadResult.mediaType,
                downloadResult.size
              )
            }
        }

      videoMetadata = VideoMetadata(processedUri, videoId, videoSite, title, duration, size, thumbnail)

      _ <- transaction {
        fileResourceDao
          .insert(thumbnail)
          .productR(videoMetadataDao.insert(videoMetadata))
      }
    } yield videoMetadata

  override def analyze(uri: Uri): F[VideoAnalysisResult] =
    VideoSite.fromUri(uri).toType[F, Throwable]
      .flatMap {
        case customVideoSite: CustomVideoSite =>
          for {
            processedUri <- customVideoSite.processUri[F](uri)
            document <- customVideoSiteHtmlDocument(processedUri, customVideoSite)
            videoAnalysisResult <- analyze(processedUri, customVideoSite).run(WebPage(uri, document))
          }
          yield videoAnalysisResult

        case VideoSite.Local =>
          ApplicativeError[F, Throwable].raiseError {
            ValidationException("Unable to analyse local URLs")
          }

        case videoSite =>
          videoSite.processUri[F](uri).flatMap(youTubeVideoDownloader.videoInformation)
      }

  override def downloadUri(uri: Uri): F[Uri] =
    VideoSite.fromUri(uri).toType[F, Throwable]
      .flatMap {
        case customVideoSite: CustomVideoSite =>
          for {
            document <- customVideoSiteHtmlDocument(uri, customVideoSite)
            downloadUri <- customVideoSite.downloadUri[F].run(WebPage(uri, document))
          }
          yield downloadUri

        case _ =>
          ApplicativeError[F, Throwable].raiseError {
            ValidationException("Download URLs can only be fetched for custom video sites")
          }
      }

  private def customVideoSiteHtmlDocument(uri: Uri, customVideoSite: CustomVideoSite): F[Document] =
    for {
      html <-
        customVideoSite match {
          case spaCustomVideoSite: CustomVideoSite.SpaCustomVideoSite =>
            spaSiteRenderer.render(uri, spaCustomVideoSite.readyCssSelectors)

          case _ => client.run(GET(uri)).use(_.as[String])
        }

      document <- Sync[F].catchNonFatal(Jsoup.parse(html))
    } yield document


  def analyze(uri: Uri, customVideoSite: CustomVideoSite): Selector[F, VideoAnalysisResult] =
    for {
      videoTitle <- customVideoSite.title[F]
      thumbnailUri <- customVideoSite.thumbnailUri[F]
      duration <- customVideoSite.duration[F]

      downloadUri <- customVideoSite.downloadUri[F]

      _ <- Kleisli.liftF(logger.info[F](s"Download uri = $downloadUri for videoUri = $uri"))

      size <- Kleisli.liftF {
        client
          .run(HEAD(downloadUri))
          .use { response =>
            if (response.status.isSuccess)
              Http4sUtils.header[F, `Content-Length`].map(_.length).run(response)
            else
              ApplicativeError[F, Throwable].raiseError[Long] {
                ExternalServiceException(s"Failed ${response.status} response for HEAD $downloadUri")
              }
          }
      }
    } yield VideoAnalysisResult(uri, customVideoSite, videoTitle, duration, size, thumbnailUri)

  override def videoDurationFromPath(videoPath: String): F[FiniteDuration] =
    cliCommandRunner.run(s"""ffprobe -i "$videoPath" -show_entries format=duration -v quiet -print_format csv="p=0"""")
      .compile
      .string
      .flatMap { output =>
        output.toDoubleOption match {
          case None =>
            ApplicativeError[F, Throwable].raiseError {
              ValidationException(s"Unable to determine video duration for file at $videoPath")
            }

          case Some(seconds) =>
            Applicative[F].pure {
              FiniteDuration(math.floor(seconds).toLong, TimeUnit.SECONDS)
            }
        }
      }
}
