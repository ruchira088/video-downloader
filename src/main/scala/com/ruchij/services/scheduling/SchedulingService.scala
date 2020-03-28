package com.ruchij.services.scheduling

import com.ruchij.daos.scheduling.models.VideoMetadata
import org.http4s.Uri

trait SchedulingService[F[_]] {
  def schedule(uri: Uri): F[VideoMetadata]
}
