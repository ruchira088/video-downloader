package com.ruchij.core.config

import com.comcast.ip4s.{Host, Port}
import enumeratum.{Enum, EnumEntry}
import org.http4s.Uri
import org.joda.time.{DateTime, LocalTime}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import scala.collection.Factory
import scala.reflect.{ClassTag, classTag}
import scala.util.Try

object PureConfigReaders {
  private val ListSeparator = ";"

  implicit val localTimePureConfigReader: ConfigReader[LocalTime] =
    stringConfigParserTry { localTime =>
      Try(LocalTime.parse(localTime))
    }

  implicit val dateTimePureConfigReader: ConfigReader[DateTime] =
    stringConfigParserTry { dateTime =>
      Try(DateTime.parse(dateTime))
    }

  implicit val hostConfigReader: ConfigReader[Host] = optionParser(Host.fromString)

  implicit val portConfigReader: ConfigReader[Port] = optionParser(Port.fromString)

  implicit val uriPureConfigReader: ConfigReader[Uri] =
    ConfigReader.fromNonEmptyString { input =>
      Uri.fromString(input).left.map(error => CannotConvert(input, classOf[Uri].getSimpleName, error.message))
    }

  implicit def stringListConfigReader[Itr[x] <: IterableOnce[x]](
    implicit factory: Factory[String, Itr[String]]
  ): ConfigReader[Itr[String]] =
    ConfigReader[Option[String]].map {
      _.fold(factory.fromSpecific(List.empty)) { string =>
        factory.fromSpecific {
          string.split(ListSeparator).map(_.trim).filter(_.nonEmpty)
        }
      }
    }

  implicit def enumPureConfigReader[A <: EnumEntry: ClassTag](implicit enumValues: Enum[A]): ConfigReader[A] =
    ConfigReader.fromNonEmptyStringOpt[A] { value =>
      enumValues.values.find(_.entryName.equalsIgnoreCase(value))
    }

  def stringConfigParserTry[A](parser: String => Try[A])(implicit classTag: ClassTag[A]): ConfigReader[A] =
    ConfigReader.fromNonEmptyString { value =>
      parser(value).toEither.left.map { throwable =>
        CannotConvert(value, classTag.runtimeClass.getSimpleName, throwable.getMessage)
      }
    }

  private def optionParser[A: ClassTag](parser: String => Option[A]): ConfigReader[A] =
    ConfigReader.fromNonEmptyString { input =>
      parser(input).toRight(CannotConvert(input, classTag[A].runtimeClass.getSimpleName, "Parser failed"))
    }
}
