Feature: Health check
  Monitoring tools can ask whether the service is up.

  Scenario: The service reports that it is up
    When I request "/actuator/health"
    Then the response status is 200
    And the response field "status" is "UP"
