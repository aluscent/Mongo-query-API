package com.cimri.fileprocessor

import cats.effect.{IO, Resource}
import com.cimri.model.{Feed, FeedsFile}
import spray.json._
import com.github.tototoshi.csv._
import scala.io.Source

object FileHandler {
  /** Reads at most batchSize data rows at a time; all blocking I/O is suspended.
    * Invalid rows fail with a line number instead of silently truncating a zip.
    */
  def foreachCsvBatch(path: String, batchSize: Int = 500)(consume: List[Map[String, String]] => IO[Unit]): IO[Unit] =
    IO.raiseWhen(batchSize <= 0)(new IllegalArgumentException("batchSize must be positive")) *>
    Resource.make(IO.blocking(CSVReader.open(Source.fromFile(path)("UTF-8"))))(reader => IO.blocking(reader.close()))
      .use { reader =>
        IO.blocking {
          val rows = reader.iterator
          require(rows.hasNext, "CSV file is empty")
          val header = rows.next()
          require(header.nonEmpty && header.forall(_.trim.nonEmpty) && header.distinct.size == header.size,
            "CSV headers must be unique and non-empty")
          (header, rows.zipWithIndex)
        }.flatMap { case (header, rows) =>
          def loop: IO[Unit] = IO.blocking {
            rows.take(batchSize).map { case (row, index) =>
              require(row.size == header.size, s"CSV row ${index + 2} has ${row.size} fields; expected ${header.size}")
              header.zip(row).toMap
            }.toList
          }.flatMap(batch => if (batch.isEmpty) IO.unit else consume(batch) *> IO.defer(loop))
          loop
        }
      }

  // Convenience for small callers/tests; ingestion uses foreachCsvBatch directly.
  def getCsvFile(path: String): IO[List[Map[String, String]]] = IO.defer {
    val result = List.newBuilder[Map[String, String]]
    foreachCsvBatch(path)(batch => IO { result ++= batch; () }).as(result).map(_.result())
  }

  import com.cimri.implicits.FeedImplicits._
  def getJsonFile(path: String): IO[List[Feed]] = Resource
    .make(IO.blocking(Source.fromFile(path)("UTF-8")))(src => IO.blocking(src.close()))
    .use(file => IO.blocking(file.mkString.parseJson.convertTo[FeedsFile].feeds))
}
