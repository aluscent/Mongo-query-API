package com.cimri

import cats.effect.{ExitCode, IO, IOApp}
import com.cimri.fileprocessor.FileHandler.{foreachCsvBatch, getJsonFile}
import cats.syntax.all._
import java.time.ZoneOffset
import java.nio.file.Paths
import com.cimri.db.MongoWrapper
import com.cimri.model.Feed
import org.mongodb.scala.bson.BsonDateTime
import org.mongodb.scala.bson.collection.immutable.Document

import java.text.SimpleDateFormat
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

/**
 * Main entry point for the application file processing part.
 */
object MainFileProcessor extends IOApp {

  /**
   * Splits feed files by day.
   * Useful in combination with [[processBaseAndInsertIntoMongoByDay]].
   * @param listOfFeed list of the entries related to the feed files
   * @return A map of date to a list of all respecting feed file entries
   */
  def splitFeedDayByDay(listOfFeed: List[Feed]): Map[String, List[Feed]] = listOfFeed
    .groupBy(feed => feed.version.toInstant.atZone(ZoneOffset.UTC).toLocalDate.toString)


  /**
   * Inserting one file by its name and path
   * @param mongo the MongoDB interface
   * @param path the path to feed file
   * @param feed the entry related to this feed file
   */
  def insertOneFile(mongo: MongoWrapper, path: String, feed: Feed): IO[Unit] = {
    val version = "version" -> BsonDateTime(feed.version)

    val root = Paths.get(path).toAbsolutePath.normalize()
    val file = root.resolve(feed.name).normalize()
    IO.raiseWhen(!file.startsWith(root))(new IllegalArgumentException("Feed path escapes input directory")) *>
      foreachCsvBatch(file.toString) { rows =>
        IO {
          require(rows.forall(_.get("id").exists(_.trim.nonEmpty)), "Every row needs a non-empty id")
          rows.map(record => Document(record.map { case (key, value) => key -> value }) + version)
        }.flatMap(mongo.upsertHistory)
      }
  }

  /**
   * Inserting data related to each day of feed into MongoDB.
   * It checks for the data to be exactly for one day.
   * @param path the path to feed file
   * @param listOfFeed list of the entries related to the feed files
   * @param mongo the mongo interface
   */
  def processBaseAndInsertIntoMongoByDay(path: String, listOfFeed: List[Feed])(mongo: MongoWrapper): IO[List[Unit]] = {

    IO.defer {
      val ordered = listOfFeed.sortBy(_.version.getTime)
      require(ordered.nonEmpty && ordered.head.feedType == "base" &&
        ordered.tail.forall(_.feedType == "delta"), "Expected one base followed by deltas")
      ordered.traverse(feed => insertOneFile(mongo, path, feed))
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {

    args match {
      case manifest :: directory :: Nil =>
        for {
          list <- getJsonFile(manifest)
          _ <- MongoWrapper(sys.env.getOrElse("MONGO_URI", "mongodb://localhost:27017"), "cimri", "price_changes").use { mongo =>
            mongo.ensureHistoryIndex *> processBaseAndInsertIntoMongoByDay(directory, list)(mongo)
          }
        } yield ExitCode.Success
      case _ => IO.println("Usage: MainFileProcessor <feeds.json> <feed-directory>").as(ExitCode.Error)
    }
  }
}
