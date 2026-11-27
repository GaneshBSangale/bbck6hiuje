package com.example.weather.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

// ApiExceptionHandler turns exceptions into readable Problem Details responses.
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    // SCENARIO: one of our own exceptions keeps its status, title and message.
    @Test
    void keepsStatusTitleAndDetailOfOwnExceptions() {
        // ARRANGE
        WeatherApiException exception = new WeatherApiException.InvalidDateRange("from must be before to.");

        // ACT
        ProblemDetail problemDetail = handler.handleWeatherApiException(exception);

        // ASSERT
        assertEquals(400, problemDetail.getStatus());
        assertEquals("Invalid date range", problemDetail.getTitle());
        assertEquals("from must be before to.", problemDetail.getDetail());
    }

    // SCENARIO: a unique key violation that survived the service's retry becomes a 409 with a retry hint.
    @Test
    void turnsUniqueKeyViolationIntoConflict() {
        // ARRANGE
        DataIntegrityViolationException exception = new DataIntegrityViolationException("duplicate key");

        // ACT
        ProblemDetail problemDetail = handler.handleDataIntegrityViolation(exception);

        // ASSERT
        assertEquals(409, problemDetail.getStatus());
        assertEquals("Conflicting reading", problemDetail.getTitle());
        assertEquals(
                "A reading for this sensor, metric and time was saved at the same moment. Please retry.",
                problemDetail.getDetail());
    }

    // SCENARIO: a badly encoded query string becomes a 400 with a fixed detail, so the raw query is never repeated.
    @Test
    void turnsBadQueryEncodingIntoBadRequest() {
        // ARRANGE
        String expectedDetail = "The query string could not be read. Check that it is URL-encoded correctly.";

        // ACT
        ProblemDetail problemDetail = handler.handleInvalidParameter();

        // ASSERT
        assertEquals(400, problemDetail.getStatus());
        assertEquals(expectedDetail, problemDetail.getDetail());
    }

    // SCENARIO: an unexpected error becomes a 500 that does not reveal internal details.
    @Test
    void hidesInternalDetailsOfUnexpectedErrors() {
        // ARRANGE
        IllegalStateException exception = new IllegalStateException("connection string with secrets");

        // ACT
        ProblemDetail problemDetail = handler.handleUnexpectedException(exception);

        // ASSERT
        assertEquals(500, problemDetail.getStatus());
        assertEquals("Something went wrong. Please try again later.", problemDetail.getDetail());
    }
}
