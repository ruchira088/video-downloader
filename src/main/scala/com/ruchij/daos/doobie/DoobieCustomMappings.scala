package com.ruchij.daos.doobie

import java.sql.Timestamp
import java.util.concurrent.TimeUnit

import cats.Show
import doobie.implicits.javasql._
import doobie.util.{Get, Put}
import enumeratum.{Enum, EnumEntry}
import org.http4s.Uri
import org.joda.time.DateTime

import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.TypeTag

object DoobieCustomMappings {
  private implicit def stringShow[A]: Show[A] = Show.fromToString[A]

  implicit def enumPut[A <: EnumEntry]: Put[A] = Put[String].contramap[A](_.entryName)

  implicit def enumGet[A <: EnumEntry: TypeTag](implicit enumValues: Enum[A]): Get[A] =
    Get[String].temap(text => enumValues.withNameInsensitiveEither(text).left.map(_.getMessage()))

  implicit val uriPut: Put[Uri] = Put[String].contramap[Uri](_.renderString)

  implicit val uriGet: Get[Uri] = Get[String].temap(text => Uri.fromString(text).left.map(_.message))

  implicit val finiteDurationPut: Put[FiniteDuration] = Put[Long].contramap[FiniteDuration](_.toMillis)

  implicit val finiteDurationGet: Get[FiniteDuration] =
    Get[Long].map(number => FiniteDuration(number, TimeUnit.MILLISECONDS))

  implicit val dateTimePut: Put[DateTime] =
    Put[Timestamp].tcontramap[DateTime](dateTime => new Timestamp(dateTime.getMillis))

  implicit val dateTimeGet: Get[DateTime] = Get[Timestamp].map(timestamp => new DateTime(timestamp.getTime))
}
