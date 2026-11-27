package com.example.weather.model;

import com.example.weather.exception.WeatherApiException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Statistics a query can ask for.
 * 1. Matching is case-insensitive, so "AVG" and "avg" both work.
 * 2. The database computes all four at once; the service picks the requested one.
 */
public enum Statistic {
    MIN,
    MAX,
    SUM,
    AVG;

    public static Statistic fromApiName(String apiName) {
        String normalizedName = apiName.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(statistic -> statistic.apiName().equals(normalizedName))
                .findFirst()
                .orElseThrow(() -> new WeatherApiException.UnknownStatistic(
                        "Unknown statistic '" + apiName + "'. Allowed statistics: " + allowedNames() + "."));
    }

    private static String allowedNames() {
        return Arrays.stream(values()).map(Statistic::apiName).collect(Collectors.joining(", "));
    }

    public String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
