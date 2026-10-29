# Versioned Feed Query API

A Scala/Cats Effect importer and http4s API for exploring historical product feeds
in MongoDB. It demonstrates resource ownership, acknowledged asynchronous writes,
bounded CSV ingestion and replayable version history.

Use JDK 17, sbt and MongoDB (the supplied `docker-compose.yml` starts a local instance).

```sh
docker compose up -d
sbt 'runMain com.cimri.MainFileProcessor src/test/resources/feeds.json src/test/resources'
sbt 'runMain com.cimri.MainAPI'
```

Both entrypoints accept `MONGO_URI` (default `mongodb://localhost:27017`). Database
and collection are `cimri.price_changes`. The importer takes a manifest path and
feed directory rather than a developer-specific absolute path.

Examples:

```text
GET http://localhost:12345/api/getById/1
GET http://localhost:12345/api/getByIdAndDate?id=1&startDate=2024-03-27&endDate=2024-03-27
```

The checked-in CSVs use larger product IDs; replace `1` with a real input ID.
A base plus later deltas creates historical versions; importing the same manifest
again replaces matching versions. The integration suite checks count and latest
price after replay, and confirms that a rejected duplicate insert fails its IO.

## Reliability contract and runnable checks

```sh
sbt 'testOnly com.cimri.FileProcessorTester com.cimri.EffectsAndRoutesSpec'
# Docker required for actual MongoDB acknowledgement/replay checks:
sbt 'testOnly com.cimri.APIIntegrationSpec'
sbt 'runMain com.cimri.MainFileProcessor src/test/resources/feeds.json src/test/resources'
```

Set `MONGO_URI` for a non-default database. The importer owns one client, awaits
all writes, streams 500-row batches and processes a timestamp-ordered base followed
by deltas. Blank IDs, ragged rows and duplicate headers fail visibly.

History is keyed by `(id, version)` with a unique index and replacement upserts.
Replaying a file converges to one record per key. Existing duplicates must be
cleaned before the index can be created. A file is not an atomic transaction;
a later invalid row/write failure can leave earlier batches committed. Replay
after correcting the input. Concurrent feeds with conflicting values for the same
key need an external ordering policy. No throughput benchmark is claimed.

`getById` returns the newest version. Date ranges use strict ISO dates, UTC, and
include the complete end day. Invalid/reversed dates return 400, unknown routes
404. JSON responses contain objects, not JSON-encoded strings. CI runs both unit
and Docker integration tests; test resource paths are relative to the repository.
