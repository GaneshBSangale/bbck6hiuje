package com.example.weather.api;

import com.example.weather.model.Metric;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies of the API.
 * 1. They are kept in one file because each one is only a few lines.
 * 2. Every value in a response carries its unit; statistics are rounded to two decimals.
 * 3. The schema examples match the README, so Swagger UI shows real values instead of placeholders.
 */
public final class ApiModels {

    private static final String METRIC_VALUES_EXAMPLE = """
            {"temperature": {"value": 21.5, "unit": "°C"}, "humidity": {"value": 60.0, "unit": "%"}}""";

    private ApiModels() {}

    // The timestamp is text so only ISO-8601 times are accepted; an epoch number could silently mean 1970.
    public record ReadingRequest(
            @Schema(
                    description = "When the values were measured, as ISO-8601. Leave out to use the current time.",
                    example = "2026-11-20T10:00:00Z",
                    format = "date-time")
            String timestamp,

            @Schema(
                    description = "One or more of temperature (°C), humidity (%), wind_speed (m/s), pressure (hPa).",
                    example = "{\"temperature\": 21.5, \"humidity\": 60}")
            Map<String, Double> metrics) {}

    public record ReadingResponse(
            @Schema(example = "sensor-1") String sensorId,
            @Schema(example = "2026-11-20T10:00:00Z") Instant timestamp,
            @Schema(example = METRIC_VALUES_EXAMPLE) Map<String, MetricValue> metrics) {}

    // The range actually used is returned, so callers can see when the 24-hour default was applied.
    public record StatsResponse(
            @Schema(example = "avg") String stat,
            @Schema(example = "2026-11-19T00:00:00Z") Instant from,
            @Schema(example = "2026-11-26T00:00:00Z") Instant to,
            List<SensorStats> sensors) {}

    public record SensorStats(
            @Schema(example = "sensor-1") String sensorId,
            @Schema(example = METRIC_VALUES_EXAMPLE) Map<String, MetricValue> metrics) {}

    public record MetricValue(
            @Schema(example = "21.5") double value,
            @Schema(example = "°C") String unit) {

        // Two decimals are enough for weather values and keep responses easy to read.
        public static MetricValue of(Metric metric, double value) {
            double roundedValue =
                    BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
            return new MetricValue(roundedValue, metric.getUnit());
        }
    }
}
