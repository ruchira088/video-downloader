package com.ruchij.core.services.video

import cats.{Applicative, ApplicativeError, Monad, MonadError, ~>}
import cats.data.OptionT
import cats.implicits._
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.repository.RepositoryService

class VideoServiceImpl[F[_]: Monad, G[_]: MonadError[*[_], Throwable]](
  repositoryService: RepositoryService[F],
  videoDao: VideoDao[G],
  videoMetadataDao: VideoMetadataDao[G],
  snapshotDao: SnapshotDao[G],
  fileResourceDao: FileResourceDao[G],
  schedulingDao: SchedulingDao[G]
)(implicit transaction: G ~> F)
    extends VideoService[F, G] {

  override def findVideoById(videoId: String, maybeUserId: Option[String]): G[Video] =
    OptionT(videoDao.findById(videoId, maybeUserId))
      .getOrElseF {
        ApplicativeError[G, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find video with Id=$videoId")
        }
      }

  override def deleteById(videoId: String, deleteVideoFile: Boolean)(block: Video => G[Unit]): F[Video] =
    transaction {
      findVideoById(videoId, None)
        .flatMap[(Video, Seq[Snapshot])] { video =>
          block(video)
            .productR(snapshotDao.findByVideo(videoId, None))
            .flatTap { snapshots =>
              snapshotDao
                .deleteByVideo(videoId)
                .productR { snapshots.traverse(snapshot => fileResourceDao.deleteById(snapshot.fileResource.id)) }
            }
            .productL(videoDao.deleteById(videoId))
            .productL(schedulingDao.deleteById(videoId))
            .productL(videoMetadataDao.deleteById(videoId))
            .productL(fileResourceDao.deleteById(video.videoMetadata.thumbnail.id))
            .productL(fileResourceDao.deleteById(video.fileResource.id))
            .map(snapshots => video -> snapshots)
        }
    }
      .flatMap {
        case (video, snapshots) =>
          snapshots
            .traverse(snapshot => repositoryService.delete(snapshot.fileResource.path))
            .productR(repositoryService.delete(video.videoMetadata.thumbnail.path))
            .productR {
              if (deleteVideoFile) repositoryService.delete(video.fileResource.path) else Applicative[F].pure(false)
            }
            .as(video)
      }

}
