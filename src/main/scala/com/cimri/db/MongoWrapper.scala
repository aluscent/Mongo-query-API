package com.cimri.db

import cats.effect.{IO, Resource}
import org.mongodb.scala._
import org.mongodb.scala.bson.BsonDateTime
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{IndexOptions, Indexes, ReplaceOneModel, ReplaceOptions}
import scala.concurrent.Future
import java.util.Date

object MongoWrapper {
  // The by-name Future is created only when the IO runs, and failure is propagated.
  private[cimri] def await[A](future: => Future[A]): IO[A] = IO.fromFuture(IO(future))

  def apply(uri: String, db: String, collectionName: String): Resource[IO, MongoWrapper] =
    Resource.make(IO(MongoClient(uri)))(client => IO(client.close())).map { client =>
      new MongoWrapper {
        override val mongoClient: MongoClient = client
        override val cimriDB: MongoDatabase = client.getDatabase(db)
        override val collection: MongoCollection[Document] = cimriDB.getCollection(collectionName)
      }
    }
}

abstract class MongoWrapper {
  def mongoClient: MongoClient
  def cimriDB: MongoDatabase
  def collection: MongoCollection[Document]
  def closeConnection(): Unit = mongoClient.close()

  def insertOneIntoMongo(document: Document): IO[Unit] =
    MongoWrapper.await(collection.insertOne(document).toFuture()).void

  def insertManyIntoMongo(documents: Seq[_ <: Document]): IO[Unit] =
    if (documents.isEmpty) IO.unit
    else MongoWrapper.await(collection.insertMany(documents).toFuture()).void

  /** A repeated feed version replaces that version instead of duplicating history.
    * A feed is not an atomic transaction; after a partial failure replay the file.
    */
  def ensureHistoryIndex: IO[Unit] = MongoWrapper.await(collection.createIndex(
    Indexes.ascending("id", "version"), IndexOptions().unique(true)).toFuture()).void

  def upsertHistory(documents: Seq[Document]): IO[Unit] =
    if (documents.isEmpty) IO.unit
    else IO.defer {
      require(documents.forall(d => d.containsKey("id") && d.containsKey("version")),
        "History documents require id and version")
      val writes = documents.map { d =>
        ReplaceOneModel[Document](and(equal("id", d.getString("id")), equal("version", d.get("version").get)),
          d, ReplaceOptions().upsert(true))
      }
      MongoWrapper.await(collection.bulkWrite(writes).toFuture()).void
    }

  // Stable latest-version result instead of MongoDB's arbitrary first match.
  def queryById(id: String): IO[Option[Document]] =
    MongoWrapper.await(collection.find(equal("id", id))
      .sort(org.mongodb.scala.model.Sorts.descending("version")).first().headOption())

  // start inclusive, end exclusive (the API supplies midnight after the last day).
  def queryByIdAndDate(id: String, startDate: Date, endExclusive: Date): IO[Seq[Document]] =
    MongoWrapper.await(collection.find(and(equal("id", id),
      gte("version", BsonDateTime(startDate)), lt("version", BsonDateTime(endExclusive))))
      .sort(org.mongodb.scala.model.Sorts.ascending("version")).toFuture())
}
