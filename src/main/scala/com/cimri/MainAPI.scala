package com.cimri

import cats.effect.{ExitCode, IO, IOApp}
import com.cimri.api.{Endpoints, Server}
import com.cimri.db.MongoWrapper

object MainAPI extends IOApp {

  /**
   * Main entry point for the application API part.
   */
  override def run(args: List[String]): IO[ExitCode] =
    MongoWrapper(sys.env.getOrElse("MONGO_URI", "mongodb://localhost:27017"), "cimri", "price_changes")
      .use(mongo => Server(12345, Endpoints.endpoints(mongo)).getOrCreate) >>
      IO(ExitCode.Success)
}
