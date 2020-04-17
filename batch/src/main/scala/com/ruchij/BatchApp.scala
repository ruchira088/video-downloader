package com.ruchij

import java.util.concurrent.Executors

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Resource, Sync, Timer}
import com.ruchij.config.BatchServiceConfiguration
import com.ruchij.daos.doobie.DoobieTransactor
import com.ruchij.daos.scheduling.DoobieSchedulingDao
import com.ruchij.daos.video.DoobieVideoDao
import com.ruchij.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.migration.MigrationApp
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.hashing.MurmurHash3Service
import com.ruchij.services.repository.FileRepositoryService
import com.ruchij.services.scheduler.{Scheduler, SchedulerImpl}
import com.ruchij.services.scheduling.SchedulingServiceImpl
import com.ruchij.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl}
import com.ruchij.services.worker.WorkExecutorImpl
import com.ruchij.types.FunctionKTypes.eitherToF
import org.http4s.client.blaze.BlazeClientBuilder
import pureconfig.ConfigSource

import scala.concurrent.ExecutionContext

object BatchApp extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      configObjectSource <- IO.delay(ConfigSource.defaultApplication)
      batchServiceConfiguration <- BatchServiceConfiguration.parse[IO](configObjectSource)

      _ <- program[IO](batchServiceConfiguration, ExecutionContext.global).use(_.run)
    } yield ExitCode.Success

  def program[F[_]: ConcurrentEffect: ContextShift: Timer](
    batchServiceConfiguration: BatchServiceConfiguration,
    nonBlockingExecutionContext: ExecutionContext,
  ): Resource[F, Scheduler[F]] =
    for {
      client <- BlazeClientBuilder[F](nonBlockingExecutionContext).resource

      ioThreadPool <- Resource.liftF(Sync[F].delay(Executors.newCachedThreadPool()))
      ioBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(ioThreadPool))

      processorCount <- Resource.liftF(Sync[F].delay(Runtime.getRuntime.availableProcessors()))
      cpuBlockingThreadPool <- Resource.liftF(Sync[F].delay(Executors.newFixedThreadPool(processorCount)))
      cpuBlocker = Blocker.liftExecutionContext(ExecutionContext.fromExecutor(cpuBlockingThreadPool))

      transactor <- Resource.liftF {
        DoobieTransactor.create[F](batchServiceConfiguration.databaseConfiguration, ioBlocker)
      }

      _ <- Resource.liftF(MigrationApp.migration[F](batchServiceConfiguration.databaseConfiguration, ioBlocker))

      videoMetadataDao = new DoobieVideoMetadataDao[F](transactor)
      schedulingDao = new DoobieSchedulingDao[F](videoMetadataDao, transactor)
      videoDao = new DoobieVideoDao[F](transactor)

      repositoryService = new FileRepositoryService[F](ioBlocker)
      downloadService = new Http4sDownloadService[F](client, repositoryService)
      hashingService = new MurmurHash3Service[F](cpuBlocker)
      videoAnalysisService = new VideoAnalysisServiceImpl[F](client)
      schedulingService = new SchedulingServiceImpl[F](
        videoAnalysisService,
        schedulingDao,
        hashingService,
        downloadService,
        batchServiceConfiguration.downloadConfiguration
      )
      videoService = new VideoServiceImpl[F](videoDao, repositoryService)

      workExecutor = new WorkExecutorImpl[F](
        schedulingService,
        videoAnalysisService,
        videoService,
        downloadService,
        batchServiceConfiguration.downloadConfiguration
      )

      scheduler = new SchedulerImpl(schedulingService, workExecutor, batchServiceConfiguration.workerConfiguration)
    } yield scheduler
}
