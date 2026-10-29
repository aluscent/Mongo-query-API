package com.cimri

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.cimri.db.MongoWrapper
import com.dimafeng.testcontainers.{ForAllTestContainer, MongoDBContainer}
import org.mongodb.scala.Document
import org.mongodb.scala.bson.BsonDateTime
import org.scalatest.funsuite.AnyFunSuite

class APIIntegrationSpec extends AnyFunSuite with ForAllTestContainer {
  override val container: MongoDBContainer = MongoDBContainer()
  test("acknowledged writes, stable client ownership, latest version and replay") {
    MongoWrapper(container.container.getConnectionString, "cimri", "history").use { mongo =>
      val first = Document("id" -> "1", "version" -> BsonDateTime(1000L), "price" -> "10")
      val second = Document("id" -> "1", "version" -> BsonDateTime(2000L), "price" -> "20")
      for {
        _ <- IO(assert(mongo.mongoClient eq mongo.mongoClient))
        _ <- mongo.ensureHistoryIndex
        _ <- mongo.upsertHistory(Seq(first, second))
        _ <- mongo.upsertHistory(Seq(first, second))
        all <- mongo.queryByIdAndDate("1", new java.util.Date(0), new java.util.Date(3000))
        latest <- mongo.queryById("1")
        _ <- IO { assert(all.size == 2); assert(latest.get.getString("price") == "20") }
        failure <- mongo.insertOneIntoMongo(first).attempt
        _ <- IO(assert(failure.isLeft))
      } yield ()
    }.unsafeRunSync()
  }
}
