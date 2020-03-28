package com.ruchij.daos.video

import com.ruchij.daos.video.models.VideoMetadata

trait VideoMetadataDao[F[_]] {
  def insert(videoMetadata: VideoMetadata): F[Int]
}
