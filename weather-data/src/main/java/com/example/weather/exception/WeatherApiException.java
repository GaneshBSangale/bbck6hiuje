package com.example.weather.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for every error this API raises on purpose.
 * 1. Each nested type carries its HTTP status and a short title.
 * 2. The detail message is shown to the caller, so it must be a plain, helpful sentence.
 */
public abstract class WeatherApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    protected WeatherApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public static final class InvalidDateRange extends WeatherApiException {
        public InvalidDateRange(String detail) {
            super(HttpStatus.BAD_REQUEST, "Invalid date range", detail);
        }
    }

    public static final class InvalidReading extends WeatherApiException {
        public InvalidReading(String detail) {
            super(HttpStatus.BAD_REQUEST, "Invalid reading", detail);
        }
    }

    public static final class UnknownMetric extends WeatherApiException {
        public UnknownMetric(String detail) {
            super(HttpStatus.BAD_REQUEST, "Unknown metric", detail);
        }
    }

    public static final class UnknownStatistic extends WeatherApiException {
        public UnknownStatistic(String detail) {
            super(HttpStatus.BAD_REQUEST, "Unknown statistic", detail);
        }
    }

    public static final class ConflictingReading extends WeatherApiException {
        public ConflictingReading(String detail) {
            super(HttpStatus.CONFLICT, "Conflicting reading", detail);
        }
    }
}
