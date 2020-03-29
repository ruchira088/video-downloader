package com.ruchij.daos.video.models

import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

case class VideoMetadata(
  url: Uri,
  videoSite: VideoSite,
  title: String,
  duration: FiniteDuration,
  size: Long,
  thumbnail: Uri
)