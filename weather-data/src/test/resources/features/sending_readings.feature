Feature: Sending weather readings
  A sensor sends several metric values in one call, and the service stores them.

  Scenario: A sensor sends temperature and humidity together
    When sensor "sensor-1" sends:
      """
      { "timestamp": "2026-11-20T10:00:00Z", "metrics": { "temperature": 21.5, "humidity": 60 } }
      """
    Then the response status is 201
    And the response field "metrics.temperature.value" is "21.5"
    And the response field "metrics.temperature.unit" is "°C"
    And the response field "metrics.humidity.unit" is "%"

  Scenario: Sending the same reading twice stores it only once
    Given these readings were sent:
      | sensor   | timestamp            | temperature |
      | sensor-1 | 2026-11-20T10:00:00Z | 21.5        |
    When sensor "sensor-1" sends:
      """
      { "timestamp": "2026-11-20T10:00:00Z", "metrics": { "temperature": 21.5 } }
      """
    Then the response status is 201
    When I request "/api/v1/stats?sensors=sensor-1&metrics=temperature&stat=sum&from=2026-11-20&to=2026-11-20"
    Then the response field "sensors[0].metrics.temperature.value" is "21.5"

  Scenario: Identical readings sent at the same moment are all accepted and stored once
    When sensor "sensor-1" sends this reading 10 times at once:
      """
      { "timestamp": "2026-11-20T10:00:00Z", "metrics": { "temperature": 21.5 } }
      """
    Then every response status is 201
    And sensor "sensor-1" has 1 stored reading
    When I request "/api/v1/stats?sensors=sensor-1&metrics=temperature&stat=sum&from=2026-11-20&to=2026-11-20"
    Then the response field "sensors[0].metrics.temperature.value" is "21.5"

  Scenario: The same time with a different value is rejected
    Given these readings were sent:
      | sensor   | timestamp            | temperature |
      | sensor-1 | 2026-11-20T10:00:00Z | 21.5        |
    When sensor "sensor-1" sends:
      """
      { "timestamp": "2026-11-20T10:00:00Z", "metrics": { "temperature": 25 } }
      """
    Then the response status is 409
    And the response field "title" is "Conflicting reading"

  Scenario Outline: Invalid readings are rejected with a clear error
    When sensor "sensor-1" sends:
      """
      <body>
      """
    Then the response status is 400
    And the response field "title" is "<title>"

    Examples:
      | body                                                                      | title           |
      | { "metrics": { "humidity": 120 } }                                        | Invalid reading |
      | { "metrics": { "rain": 3 } }                                              | Unknown metric  |
      | { "timestamp": "2099-01-01T00:00:00Z", "metrics": { "temperature": 20 } } | Invalid reading |
      | { "metrics": { } }                                                        | Invalid reading |
      | { "metrics": { "temperature": 1e400 } }                                   | Invalid reading |
      | { "metrics": { "temperature": "NaN" } }                                   | Invalid reading |
      | { "metrics": { "humidity": 50, "humidity": 60 } }                         | Bad Request     |
      | { "metrics": { "temperature": "21.5" } }                                  | Bad Request     |
      | { "timestamp": "", "metrics": { "temperature": 20 } }                     | Invalid reading |
      | { "timestamp": "20/11/2026", "metrics": { "temperature": 20 } }           | Invalid reading |
      | { "metrics": not json                                                     | Bad Request     |

  Scenario: A timestamp sent as a number is rejected instead of being read as an epoch time
    When sensor "sensor-1" sends:
      """
      { "timestamp": 1e400, "metrics": { "temperature": 20 } }
      """
    Then the response status is 400
    When I request "/api/v1/stats?sensors=sensor-1&metrics=temperature&from=1970-01-01&to=1970-01-01"
    Then the response field "sensors.length()" is "0"

  Scenario: A metric name longer than 64 characters is rejected without being repeated back
    When sensor "sensor-1" sends:
      """
      { "metrics": { "averyveryveryveryveryveryveryveryveryveryveryveryveryverylongmetricname": 20 } }
      """
    Then the response status is 400
    And the response field "title" is "Bad Request"

  Scenario Outline: The request body can be at most 16 KB
    When sensor "sensor-1" sends a report padded to <bytes> bytes
    Then the response status is <status>

    Examples:
      | bytes | status |
      | 15000 | 201    |
      | 17000 | 400    |

  Scenario: The API docs show a real report and a real error
    When I request "/v3/api-docs"
    Then the response status is 200
    And the response field "components.schemas.ReadingRequest.properties.metrics.example.temperature" is "21.5"
    And the response field "paths['/api/v1/sensors/{sensorId}/readings'].post.responses['409'].content['application/problem+json'].example.title" is "Conflicting reading"
