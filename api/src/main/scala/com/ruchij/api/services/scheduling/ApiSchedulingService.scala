package com.ruchij.api.services.scheduling

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.models.DurationRange
import fs2.Stream
import org.http4s.Uri

trait ApiSchedulingService[F[_]] {
  def schedule(uri: Uri): F[ScheduledVideoDownload]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: DurationRange,
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    schedulingStatuses: Option[NonEmptyList[SchedulingStatus]]
  ): F[Seq[ScheduledVideoDownload]]

  def subscribeToDownloadProgress(groupId: String): Stream[F, DownloadProgress]

  def updateSchedulingStatus(id: String, status: SchedulingStatus): F[ScheduledVideoDownload]

  def getById(id: String): F[ScheduledVideoDownload]

  def updateWorkerStatuses(workerStatus: WorkerStatus): F[Unit]

  def updateDownloadProgress(id: String, downloadedBytes: Long): F[ScheduledVideoDownload]
}
