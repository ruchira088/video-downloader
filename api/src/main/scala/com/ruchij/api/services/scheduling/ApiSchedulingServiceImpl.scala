package com.ruchij.api.services.scheduling

import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError, ~>}
import com.ruchij.api.exceptions.ResourceConflictException
import com.ruchij.api.services.config.models.ApiConfigKey
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.SchedulingDao.notFound
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.messaging.{Publisher, Subscriber}
import com.ruchij.core.services.config.ConfigurationService
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.services.video.models.DurationRange
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherToF}
import com.ruchij.core.types.JodaClock
import fs2.Stream
import org.http4s.Uri

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class ApiSchedulingServiceImpl[F[_]: Concurrent: Timer, T[_]: MonadError[*[_], Throwable], M[_]](
  videoAnalysisService: VideoAnalysisService[F],
  scheduledVideoDownloadPublisher: Publisher[F, ScheduledVideoDownload],
  downloadProgressSubscriber: Subscriber[F, CommittableRecord[M, *], DownloadProgress],
  workerStatusPublisher: Publisher[F, WorkerStatusUpdate],
  configurationService: ConfigurationService[F, ApiConfigKey],
  schedulingDao: SchedulingDao[T]
)(implicit transaction: T ~> F)
    extends ApiSchedulingService[F] {

  private val logger = Logger[ApiSchedulingServiceImpl[F, T, M]]

  override def schedule(uri: Uri): F[ScheduledVideoDownload] =
    for {
      searchResult <- transaction {
        schedulingDao.search(
          None,
          Some(NonEmptyList.of(uri)),
          DurationRange.All,
          0,
          1,
          SortBy.Date,
          Order.Descending,
          None
        )
      }

      _ <- if (searchResult.nonEmpty)
        ApplicativeError[F, Throwable].raiseError(ResourceConflictException(s"$uri has already been scheduled"))
      else Applicative[F].unit

      videoMetadataResult <- videoAnalysisService.metadata(uri)
      timestamp <- JodaClock[F].timestamp

      scheduledVideoDownload = ScheduledVideoDownload(
        timestamp,
        timestamp,
        SchedulingStatus.Queued,
        0,
        videoMetadataResult.value,
        None
      )

      _ <- transaction(schedulingDao.insert(scheduledVideoDownload))
      _ <- scheduledVideoDownloadPublisher.publishOne(scheduledVideoDownload)
    } yield scheduledVideoDownload

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: DurationRange,
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatuses: Option[NonEmptyList[SchedulingStatus]]
  ): F[Seq[ScheduledVideoDownload]] =
    if (sortBy == SortBy.WatchTime)
      ApplicativeError[F, Throwable].raiseError {
        new IllegalArgumentException("Searching for scheduled videos by watch_time is not valid")
      } else
      transaction(
        schedulingDao.search(term, videoUrls, durationRange, pageNumber, pageSize, sortBy, order, schedulingStatuses)
      )

  override def subscribeToDownloadProgress(groupId: String): Stream[F, DownloadProgress] =
    downloadProgressSubscriber.subscribe(groupId)
      .observe {
        _
          .groupWithin(20, 5 seconds)
          .evalMap {
            chunk =>
              downloadProgressSubscriber.commit(chunk)
                .productR {
                  logger.debug[F] {
                    s"DownloadProgressSubscriber(groupId=$groupId) committed ${chunk.size} messages"
                  }
                }
          }
      }
      .map { case CommittableRecord(value, _) => value }

  override def updateSchedulingStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload] =
    JodaClock[F].timestamp
      .map { timestamp =>
        for {
          scheduledVideoDownload <- OptionT(schedulingDao.getById(id))
          _ <- OptionT.liftF(scheduledVideoDownload.status.validateTransition(status).toType[T, Throwable])
          updated <- OptionT(schedulingDao.updateSchedulingStatusById(id, status, timestamp))
        }
        yield updated
      }
      .flatMap { maybeUpdatedT =>
        OptionT(transaction(maybeUpdatedT.value))
          .getOrElseF { ApplicativeError[F, Throwable].raiseError(notFound(id)) }
      }
      .flatTap(scheduledVideoDownloadPublisher.publishOne)

  override def getById(id: String): F[ScheduledVideoDownload] =
    OptionT(transaction(schedulingDao.getById(id)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError(notFound(id))
      }

  override def updateWorkerStatus(workerStatus: WorkerStatus): F[Unit] =
    configurationService.put(ApiConfigKey.WorkerStatus, workerStatus)
      .productR {
        workerStatusPublisher.publishOne(WorkerStatusUpdate(workerStatus))
      }

  override val getWorkerStatus: F[WorkerStatus] =
    configurationService.get[WorkerStatus, ApiConfigKey](ApiConfigKey.WorkerStatus).map(_.getOrElse(WorkerStatus.Available))

  override def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload] =
    for {
      timestamp <- JodaClock[F].timestamp
      result <- OptionT {
        transaction { schedulingDao.updateDownloadProgress(id, downloadedBytes, timestamp) }
      }.getOrElseF(ApplicativeError[F, Throwable].raiseError(notFound(id)))
    } yield result
}
