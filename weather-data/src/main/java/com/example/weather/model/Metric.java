package com.example.weather.model;

import com.example.weather.exception.WeatherApiException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Weather metrics a sensor may report.
 * 1. Each metric has one fixed unit, so stored values are always comparable.
 * 2. The limits reject values no real sensor could measure, which usually means a faulty sensor.
 */
public enum Metric {
    TEMPERATURE("°C", -90, 60),
    HUMIDITY("%", 0, 100),
    WIND_SPEED("m/s", 0, 120),
    PRESSURE("hPa", 870, 1085);

    private final String unit;
    private final int minValue;
    private final int maxValue;

    Metric(String unit, int minValue, int maxValue) {
        this.unit = unit;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public static Metric fromApiName(String apiName) {
        String normalizedName = apiName.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(metric -> metric.apiName().equals(normalizedName))
                .findFirst()
                .orElseThrow(() -> new WeatherApiException.UnknownMetric(
                        "Unknown metric '" + apiName + "'. Allowed metrics: " + allowedNames() + "."));
    }

    public static String allowedNames() {
        return Arrays.stream(values()).map(Metric::apiName).collect(Collectors.joining(", "));
    }

    public String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String getUnit() {
        return unit;
    }

    public boolean accepts(double value) {
        return value >= minValue && value <= maxValue;
    }

    public String describeLimits() {
        return minValue + " to " + maxValue + " " + unit;
    }
}
