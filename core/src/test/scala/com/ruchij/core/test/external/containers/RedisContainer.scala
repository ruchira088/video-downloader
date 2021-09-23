package com.ruchij.core.test.external.containers

import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.config.RedisConfiguration
import org.testcontainers.containers.GenericContainer

class RedisContainer extends GenericContainer[RedisContainer]("bitnami/redis:6.0.15") {
  private val Port = 6379
  private val Password = "ThisIsThePassword"

  withEnv("REDIS_PASSWORD", Password)
  withExposedPorts(Port)

  def redisConfiguration[F[_]: Sync]: F[RedisConfiguration] =
    for {
      port <- Sync[F].delay(getMappedPort(Port))
      host <- Sync[F].delay(getHost)
    } yield RedisConfiguration(host, port, Some(Password))

}
