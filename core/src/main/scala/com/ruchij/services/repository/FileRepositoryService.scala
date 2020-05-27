package com.ruchij.services.repository

import java.nio.file.{Path, Paths, StandardOpenOption}

import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import fs2.Stream
import fs2.io.file.{readRange, writeAll, size => fileSize}

class FileRepositoryService[F[_]: Sync: ContextShift](ioBlocker: Blocker) extends RepositoryService[F] {

  override def write(key: String, data: Stream[F, Byte]): Stream[F, Unit] =
    for {
      path <- Stream.eval(FileRepositoryService.parsePath[F](key))
      exists <- Stream.eval(fileExists(path))

      result <- data.through {
        writeAll(path, ioBlocker, if (exists) List(StandardOpenOption.APPEND) else List(StandardOpenOption.CREATE))
      }
    }
    yield result

  override def read(key: String, start: Option[Long], end: Option[Long]): F[Option[Stream[F, Byte]]] =
    FileRepositoryService.parsePath[F](key)
      .flatMap { path =>
        fileExists(path)
          .flatMap { exists =>
            if (exists)
              Sync[F].delay(
                Some(readRange(path, ioBlocker, FileRepositoryService.CHUNK_SIZE, start.getOrElse(0), end.getOrElse(Long.MaxValue)))
              )
            else
              Applicative[F].pure(None)
          }
      }

  override def size(key: String): F[Option[Long]] =
    for {
      path <- FileRepositoryService.parsePath[F](key)
      exists <- fileExists(path)

      bytes <- if (exists) fileSize(ioBlocker, path).map[Option[Long]](Some.apply) else Applicative[F].pure[Option[Long]](None)
    }
    yield bytes

  def fileExists(path: Path): F[Boolean] =
    Sync[F].delay(path.toFile.exists())
}

object FileRepositoryService {
  val CHUNK_SIZE: Int = 4096

  def parsePath[F[_]](path: String)(implicit applicativeError: ApplicativeError[F, Throwable]): F[Path] =
    applicativeError.catchNonFatal(Paths.get(path))
}
