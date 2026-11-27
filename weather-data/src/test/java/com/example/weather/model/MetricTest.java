package com.example.weather.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.weather.exception.WeatherApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Metric is the fixed list of weather metrics, each with a unit and realistic limits.
class MetricTest {

    // SCENARIO: a metric name is found regardless of letter case and surrounding spaces.
    @Test
    void findsMetricIgnoringCaseAndSpaces() {
        // ARRANGE
        String apiName = " Wind_Speed ";

        // ACT
        Metric metric = Metric.fromApiName(apiName);

        // ASSERT
        assertEquals(Metric.WIND_SPEED, metric);
    }

    // SCENARIO: an unknown name is rejected and the message lists the allowed metrics.
    @Test
    void rejectsUnknownMetricName() {
        // ARRANGE
        String apiName = "rain";

        // ACT AND ASSERT
        WeatherApiException exception =
                assertThrows(WeatherApiException.UnknownMetric.class, () -> Metric.fromApiName(apiName));

        // ASSERT
        assertEquals(
                "Unknown metric 'rain'. Allowed metrics: temperature, humidity, wind_speed, pressure.",
                exception.getMessage());
    }

    // SCENARIO: values on and inside the humidity limits are accepted.
    @ParameterizedTest
    @ValueSource(doubles = {0, 55.5, 100})
    void acceptsValuesWithinTheLimits(double value) {
        // ARRANGE
        Metric metric = Metric.HUMIDITY;

        // ACT
        boolean accepted = metric.accepts(value);

        // ASSERT
        assertTrue(accepted);
    }

    // SCENARIO: values just outside the humidity limits, and NaN, are rejected.
    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 100.1, Double.NaN})
    void rejectsValuesOutsideTheLimits(double value) {
        // ARRANGE
        Metric metric = Metric.HUMIDITY;

        // ACT
        boolean accepted = metric.accepts(value);

        // ASSERT
        assertFalse(accepted);
    }

    // SCENARIO: the limits are described with the unit, for use in error messages.
    @Test
    void describesLimitsWithUnit() {
        // ARRANGE
        Metric metric = Metric.TEMPERATURE;

        // ACT
        String description = metric.describeLimits();

        // ASSERT
        assertEquals("-90 to 60 °C", description);
    }

    // SCENARIO: the API name is the lower-case enum name.
    @Test
    void usesLowerCaseApiName() {
        // ARRANGE
        Metric metric = Metric.WIND_SPEED;

        // ACT
        String apiName = metric.apiName();

        // ASSERT
        assertEquals("wind_speed", apiName);
    }
}
