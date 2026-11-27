package com.example.weather.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

// WeatherApiException is the base of the API's own errors; each nested type fixes its HTTP status and title.
class WeatherApiExceptionTest {

    static Stream<Arguments> exceptionTypes() {
        return Stream.of(
                Arguments.of(
                        new WeatherApiException.InvalidDateRange("detail"),
                        HttpStatus.BAD_REQUEST,
                        "Invalid date range"),
                Arguments.of(
                        new WeatherApiException.InvalidReading("detail"), HttpStatus.BAD_REQUEST, "Invalid reading"),
                Arguments.of(new WeatherApiException.UnknownMetric("detail"), HttpStatus.BAD_REQUEST, "Unknown metric"),
                Arguments.of(
                        new WeatherApiException.UnknownStatistic("detail"),
                        HttpStatus.BAD_REQUEST,
                        "Unknown statistic"),
                Arguments.of(
                        new WeatherApiException.ConflictingReading("detail"),
                        HttpStatus.CONFLICT,
                        "Conflicting reading"));
    }

    // SCENARIO: each exception type reports its own status and title, and keeps the detail message.
    @ParameterizedTest
    @MethodSource("exceptionTypes")
    void carriesStatusTitleAndDetail(WeatherApiException exception, HttpStatus expectedStatus, String expectedTitle) {
        // ARRANGE
        String expectedDetail = "detail";

        // ACT
        String detail = exception.getMessage();

        // ASSERT
        assertEquals(expectedDetail, detail);
        assertEquals(expectedStatus, exception.getStatus());
        assertEquals(expectedTitle, exception.getTitle());
    }
}
