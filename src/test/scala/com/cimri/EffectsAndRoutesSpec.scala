package com.cimri

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.cimri.db.MongoWrapper
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.{Future, Promise}
import org.http4s.{Request, Method, Status, Uri}
import org.http4s.implicits._
import java.util.Date

class EffectsAndRoutesSpec extends AnyFunSuite {
  test("database Future is lazy, awaited, and errors propagate") {
    var calls = 0
    val promise = Promise[Int]()
    val effect = MongoWrapper.await { calls += 1; promise.future }
    assert(calls == 0)
    val observed = (for {
      fiber <- effect.start
      _ <- IO.cede
      _ <- IO(promise.success(42))
      value <- fiber.joinWithNever
    } yield value).unsafeRunSync()
    assert(observed == 42 && calls == 1)
    intercept[IllegalStateException] {
      MongoWrapper.await(Future.failed(new IllegalStateException("write failed"))).unsafeRunSync()
    }
  }
  test("invalid dates, reversed ranges and missing fields never call the database") {
    val mongo = new MongoWrapper {
      def mongoClient = throw new AssertionError("not used")
      def cimriDB = throw new AssertionError("not used")
      def collection = throw new AssertionError("invalid input reached database")
    }
    val http = api.Endpoints.endpoints(mongo).orNotFound
    for (query <- List("id=1&startDate=2024-02-30&endDate=2024-03-01",
      "id=1&startDate=2024-03-02&endDate=2024-03-01", "id=1")) {
      val res = http.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/getByIdAndDate?" + query))).unsafeRunSync()
      assert(res.status == Status.BadRequest)
    }
    assert(http.run(Request[IO](Method.GET, uri"/missing")).unsafeRunSync().status == Status.NotFound)
  }
  test("end date includes its entire UTC day and responses are JSON objects") {
    var seen: Option[(Date, Date)] = None
    val mongo = new MongoWrapper {
      def mongoClient = throw new AssertionError("not used")
      def cimriDB = throw new AssertionError("not used")
      def collection = throw new AssertionError("not used")
      override def queryByIdAndDate(id: String, start: Date, end: Date) = IO {
        seen = Some((start, end))
        Seq(org.mongodb.scala.Document("id" -> id))
      }
    }
    val http = api.Endpoints.endpoints(mongo).orNotFound
    val response = http.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/getByIdAndDate?id=1&startDate=2024-03-01&endDate=2024-03-01"))).unsafeRunSync()
    assert(response.status == Status.Ok)
    assert(seen.get._2.getTime - seen.get._1.getTime == 86400000L)
    assert(response.as[String].unsafeRunSync() == "[{\"id\":\"1\"}]")
  }
}
