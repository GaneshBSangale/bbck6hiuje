Feature: Querying weather statistics
  A client asks for one statistic of chosen metrics, per sensor, over a date range.

  Background:
    Given these readings were sent:
      | sensor   | timestamp            | temperature | humidity |
      | sensor-1 | 2026-11-20T10:00:00Z | 20          | 60       |
      | sensor-1 | 2026-11-22T10:00:00Z | 24          | 70       |
      | sensor-2 | 2026-11-21T10:00:00Z | 15          | 80       |

  Scenario: Average temperature and humidity for one sensor over a week
    When I request "/api/v1/stats?sensors=sensor-1&metrics=temperature,humidity&from=2026-11-19&to=2026-11-25"
    Then the response status is 200
    And the response field "stat" is "avg"
    And the response field "sensors.length()" is "1"
    And the response field "sensors[0].metrics.temperature.value" is "22.0"
    And the response field "sensors[0].metrics.humidity.value" is "65.0"

  Scenario: Leaving out sensors includes every sensor
    When I request "/api/v1/stats?metrics=temperature&from=2026-11-19&to=2026-11-25"
    Then the response field "sensors.length()" is "2"
    And the response field "sensors[1].sensorId" is "sensor-2"

  Scenario Outline: Each statistic is computed by the database
    When I request "/api/v1/stats?sensors=sensor-1&metrics=temperature&stat=<stat>&from=2026-11-19&to=2026-11-25"
    Then the response field "sensors[0].metrics.temperature.value" is "<value>"

    Examples:
      | stat | value |
      | min  | 20.0  |
      | max  | 24.0  |
      | sum  | 44.0  |
      | avg  | 22.0  |

  Scenario: Readings outside the range are left out
    When I request "/api/v1/stats?sensors=sensor-1&metrics=temperature&stat=max&from=2026-11-20&to=2026-11-21"
    Then the response field "sensors[0].metrics.temperature.value" is "20.0"

  Scenario: Without dates the last 24 hours are used
    Given sensor "sensor-3" sent temperature 10 2 hours ago
    And sensor "sensor-3" sent temperature 30 48 hours ago
    When I request "/api/v1/stats?sensors=sensor-3&metrics=temperature"
    Then the response status is 200
    And the response field "sensors[0].metrics.temperature.value" is "10.0"

  Scenario Outline: Invalid queries are rejected with a clear error
    When I request "<path>"
    Then the response status is 400
    And the response field "title" is "<title>"

    Examples:
      | path                                                             | title              |
      | /api/v1/stats?metrics=temperature&from=2026-10-01&to=2026-11-25 | Invalid date range |
      | /api/v1/stats?metrics=temperature&from=2026-11-19               | Invalid date range |
      | /api/v1/stats?metrics=temperature&stat=median                   | Unknown statistic  |
      | /api/v1/stats?metrics=rain                                      | Unknown metric     |
      | /api/v1/stats?sensors=sensor-1                                  | Bad Request        |
      | /api/v1/stats?metrics=%FF                                       | Bad Request        |

  Scenario: The API docs show a real query with JSON answers
    When I request "/v3/api-docs"
    Then the response status is 200
    And the response field "paths['/api/v1/stats'].get.parameters[1].example" is "temperature,humidity"
    And the response field "paths['/api/v1/stats'].get.parameters[2].schema.default" is "avg"
    And the response field "paths['/api/v1/stats'].get.responses['200'].content['application/json'].schema['$ref']" is "#/components/schemas/StatsResponse"
