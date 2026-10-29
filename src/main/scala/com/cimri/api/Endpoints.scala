package com.cimri.api

import cats.effect.IO
import cats.syntax.all._
import com.cimri.db.MongoWrapper
import io.circe.{Json, parser}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import java.time.{LocalDate, ZoneOffset}
import java.util.Date
import scala.util.Try

object Endpoints {
  private def bounds(start: String, end: String): Option[(Date, Date)] =
    Try {
      val first = LocalDate.parse(start)
      val last = LocalDate.parse(end)
      require(!last.isBefore(first), "End date precedes start date")
      (Date.from(first.atStartOfDay(ZoneOffset.UTC).toInstant),
       Date.from(last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant))
    }.toOption

  def endpoints(mongo: MongoWrapper): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "getById" / id =>
      mongo.queryById(id).flatMap {
        case Some(value) => IO.fromEither(parser.parse(value.toJson)).flatMap(Ok(_))
        case None => NotFound()
      }
    case req @ GET -> Root / "api" / "getByIdAndDate" =>
      (req.params.get("id").filter(_.trim.nonEmpty), req.params.get("startDate"), req.params.get("endDate")) match {
        case (Some(id), Some(start), Some(end)) => bounds(start, end) match {
          case Some((first, exclusiveEnd)) =>
            mongo.queryByIdAndDate(id, first, exclusiveEnd).flatMap {
              case values if values.nonEmpty =>
                values.toList.traverse(d => IO.fromEither(parser.parse(d.toJson)))
                  .flatMap(v => Ok(Json.fromValues(v)))
              case _ => NotFound()
            }
          case None => BadRequest("Use valid yyyy-MM-dd dates with startDate <= endDate.")
        }
        case _ => BadRequest("id, startDate and endDate are required.")
      }
  }
}
