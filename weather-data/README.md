# Weather Sensor API

A REST API that receives weather readings from sensors and returns statistics about them.

1. Sensors send metric values (temperature, humidity, wind speed, pressure).
2. Clients ask for the min, max, sum or average of chosen metrics, per sensor, over a date range.

Built with Java 25, Spring Boot 4.2, Maven, Spring Data JPA, Flyway and an H2 file database.

## Run it

You need Java 25. The Maven Wrapper downloads the right Maven version for you. Run all commands from the `weather-data` folder.

```bash
./mvnw spring-boot:run
```

The API starts on http://localhost:8080. Readings are saved in `./data/` and survive a restart.

Other ways to run it:

1. **Docker** (no local Java needed):
   ```bash
   docker build -t weather-sensor-api .
   docker run -p 8080:8080 weather-sensor-api
   ```
   Add `-v weather-data:/app/data` to keep readings between container runs.
2. **GitHub Codespaces**: open the repository in a codespace, then `cd weather-data`. Java 25 and Docker are set up by `.devcontainer/devcontainer.json` at the repository root.

Useful pages once it runs:

1. API docs (Swagger UI): http://localhost:8080/swagger-ui.html. Every endpoint is filled in with the README examples, so "Try it out" then "Execute" works straight away, and the main errors are shown too.
2. OpenAPI description (JSON): http://localhost:8080/v3/api-docs
3. Health check: http://localhost:8080/actuator/health

## Try it

Send a reading (the timestamp is optional and defaults to now):

```bash
curl -X POST http://localhost:8080/api/v1/sensors/sensor-1/readings \
  -H "Content-Type: application/json" \
  -d '{"timestamp": "2026-11-20T10:00:00Z", "metrics": {"temperature": 21.5, "humidity": 60}}'
```

Ask for the average temperature and humidity of sensor 1 over a week:

```bash
curl "http://localhost:8080/api/v1/stats?sensors=sensor-1&metrics=temperature,humidity&stat=avg&from=2026-11-19&to=2026-11-25"
```

```json
{
  "stat" : "avg",
  "from" : "2026-11-19T00:00:00Z",
  "to" : "2026-11-26T00:00:00Z",
  "sensors" : [ {
    "sensorId" : "sensor-1",
    "metrics" : {
      "humidity" : {
        "value" : 60.0,
        "unit" : "%"
      },
      "temperature" : {
        "value" : 21.5,
        "unit" : "°C"
      }
    }
  } ]
}
```

## API

### `POST /api/v1/sensors/{sensorId}/readings`

Stores one report from one sensor. Returns `201 Created` with the accepted values and their units.

1. `sensorId`: 1 to 64 letters, digits, `-` or `_`. A sensor exists from its first reading.
2. `timestamp`: optional ISO-8601 time as text, for example `"2026-11-20T10:00:00Z"`, stored in UTC with millisecond precision. Numbers (epoch times) and times in the future are rejected.
3. `metrics`: one or more of the metrics below, each once, with a JSON number as the value. `"21.5"` as text is rejected, and so are values that are not finite, such as `"NaN"` or `1e400`.
4. The body can be at most 16 KB.

| Metric       | Unit | Accepted values |
|--------------|------|-----------------|
| `temperature`| °C   | -90 to 60       |
| `humidity`   | %    | 0 to 100        |
| `wind_speed` | m/s  | 0 to 120        |
| `pressure`   | hPa  | 870 to 1085     |

Sending the same reading again is safe, even when the copies arrive at the same moment: it returns `201` and nothing is stored twice.
The same sensor, metric and time with a different value returns `409 Conflict`.

### `GET /api/v1/stats`

| Parameter | Required | Meaning |
|-----------|----------|---------|
| `metrics` | yes      | Comma-separated metrics, for example `temperature,humidity`. |
| `sensors` | no       | Comma-separated sensor ids. Leave out for all sensors. |
| `stat`    | no       | `min`, `max`, `sum` or `avg`. Default `avg`. |
| `from`, `to` | no    | Both or neither. Leave out for the last 24 hours. |

1. `from` and `to` accept a plain date (`2026-11-19`) or a timestamp (`2026-11-19T10:00:00Z`).
2. A plain `to` date includes that whole day. A timestamp `to` is exclusive.
3. The range must be between 1 and 31 days.
4. Results are grouped per sensor. Values are rounded to two decimals and carry their unit.

### Errors

Errors use the Problem Details format (RFC 9457) with the content type `application/problem+json`:

```json
{
  "detail" : "The date range can be at most 31 days. You asked for 45 days.",
  "status" : 400,
  "title" : "Invalid date range"
}
```

The optional `type` and `instance` fields are left out; `type` then means `about:blank`.

## Design decisions

1. **One row per metric value** (`sensor_id`, `metric`, `metric_value`, `recorded_at`). Every statistic is then one `GROUP BY` query.
2. **Statistics are computed by the database.** One query returns min, max, sum and avg per sensor and metric; no rows are loaded into memory.
3. **Fixed list of metrics (enum) with units and limits.** Typos and impossible values are rejected instead of stored. Adding a metric is a code change; in production the list could move to a table.
4. **Safe retries.** A unique key on sensor, metric and time stores each reading once, so a sensor can resend after a network error. If two identical reports race, the one that loses the insert retries once and is then treated as a repeat.
5. **Batch inserts.** Ids come from a sequence in blocks of 50, so Hibernate saves a whole report in one batch.
6. **Flyway owns the schema.** Hibernate only validates that the entities match it.
7. **All times are UTC.** Plain dates are read as UTC days.
8. **Strict input.** A metric sent twice, values sent as text, numeric timestamps and bodies over 16 KB are all rejected with 400.
9. **Fixed insert order.** A report's rows are inserted in one metric order, so two reports listing the same metrics differently cannot block each other.

## How I read the brief

1. "If not specified, the latest data should be queried" is read as the last 24 hours, the shortest range the brief allows.
2. "Between one day and a month" is read as 1 to 31 days.
3. One statistic is applied to all requested metrics; the brief's example asks for the average of two metrics.
4. "One or more (or all sensors)" returns one entry per sensor rather than one combined value.

## Tests

```bash
./mvnw verify
```

This runs formatting checks (Spotless, Palantir format), Checkstyle, all tests and a JaCoCo gate that fails below 95 % line coverage.

1. **Unit tests** (JUnit 5, Mockito) for the service, controller, exception handler, enums and models.
2. **Integration tests** in Cucumber (`src/test/resources/features`). They start the real app on a random port with an in-memory database and call it over HTTP.

Format the code before committing with `./mvnw spotless:apply`.

## Not included / next steps

1. **Security**: no authentication or rate limiting. Next step: API keys or OAuth2 per sensor and client.
2. **Production database**: PostgreSQL or TimescaleDB instead of H2, with time-based partitioning for large data. The embedded H2 database uses the default `sa` account without a password, which is only fine on a local machine.
3. **Indexes for all-sensor queries**: the unique key (`sensor_id`, `recorded_at`, `metric`) serves the duplicate check and per-sensor time ranges; queries across all sensors scan the table, so a `recorded_at` index would come next.
4. **Richer queries**: several statistics at once, one combined value across sensors, and paging for many sensors.
5. **Sensor registry**: register sensors with a name and location, and reject unknown ones.
6. **Clock drift**: a small tolerance for sensors whose clocks run slightly ahead.
