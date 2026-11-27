package com.example.weather.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.weather.api.ApiModels.MetricValue;
import com.example.weather.model.Metric;
import org.junit.jupiter.api.Test;

// ApiModels holds the request and response records; MetricValue rounds values and attaches the unit.
class ApiModelsTest {

    // SCENARIO: a value is rounded half up to two decimals.
    @Test
    void roundsValueToTwoDecimals() {
        // ARRANGE
        double rawValue = 21.455;

        // ACT
        MetricValue metricValue = MetricValue.of(Metric.TEMPERATURE, rawValue);

        // ASSERT
        assertEquals(21.46, metricValue.value());
    }

    // SCENARIO: the value carries the unit of its metric.
    @Test
    void carriesTheUnitOfTheMetric() {
        // ARRANGE
        Metric metric = Metric.PRESSURE;

        // ACT
        MetricValue metricValue = MetricValue.of(metric, 1013.25);

        // ASSERT
        assertEquals("hPa", metricValue.unit());
    }
}
