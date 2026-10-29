package com.cimri

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.cimri.fileprocessor.FileHandler._
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files

class FileProcessorTester extends AnyFunSuite {
  private def csv(text: String)(test: String => Unit): Unit = {
    val file = Files.createTempFile("feed-test-", ".csv")
    try { Files.write(file, text.getBytes("UTF-8")); test(file.toString) }
    finally Files.delete(file)
  }
  test("CSV streams bounded batches including quoted fields") {
    csv("id,price\n1,10\n2,20\n3,30\n") { path =>
      val batches = scala.collection.mutable.ListBuffer.empty[Int]
      foreachCsvBatch(path, 2)(batch => IO { batches += batch.size; () }).unsafeRunSync()
      assert(batches.toList == List(2, 1))
      assert(getCsvFile(path).unsafeRunSync().last == Map("id" -> "3", "price" -> "30"))
    }
  }
  test("empty CSV, duplicate headers and ragged rows fail") {
    for (text <- List("", "id,id\n1,2\n", "id,price\n1\n", "id,price\n1,2,3\n")) {
      csv(text)(path => intercept[IllegalArgumentException](getCsvFile(path).unsafeRunSync()))
    }
  }
  test("consumer failure stops ingestion without continuing to later batches") {
    csv("id\n1\n2\n3\n") { path =>
      var calls = 0
      intercept[IllegalStateException] {
        foreachCsvBatch(path, 1)(_ => IO { calls += 1; throw new IllegalStateException("write failed") }).unsafeRunSync()
      }
      assert(calls == 1)
    }
  }
}
