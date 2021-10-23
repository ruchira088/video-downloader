package com.ruchij.api.test

import cats.effect.IO
import io.circe.Json
import org.http4s.{MediaType, Status}

package object matchers {
  val beJsonContentType: ContentTypeMatcher[IO] = new ContentTypeMatcher[IO](MediaType.application.json)

  def haveJson(json: Json): JsonResponseMatcher = new JsonResponseMatcher(json)

  def haveStatus(status: Status): ResponseStatusMatcher[IO] = new ResponseStatusMatcher[IO](status)
}
