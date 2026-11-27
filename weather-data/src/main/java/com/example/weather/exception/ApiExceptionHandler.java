package com.example.weather.exception;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into readable Problem Details (RFC 9457) responses.
 * 1. The parent class already covers Spring's own errors: bad JSON, missing parameters, unknown paths.
 * 2. Our own exceptions keep their status, title and message; a badly encoded query string is a 400.
 * 3. Anything unexpected becomes a 500 without internal details, and the cause is logged for us.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WeatherApiException.class)
    public ProblemDetail handleWeatherApiException(WeatherApiException exception) {
        return problem(exception.getStatus(), exception.getTitle(), exception.getMessage());
    }

    // Reached only if the service's single retry also collides with a parallel request.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        LOG.warn("Rejected a reading that hit the unique key", exception);
        return problem(
                HttpStatus.CONFLICT,
                "Conflicting reading",
                "A reading for this sensor, metric and time was saved at the same moment. Please retry.");
    }

    // Tomcat's message repeats the raw query, so it is neither returned nor logged.
    @ExceptionHandler(InvalidParameterException.class)
    public ProblemDetail handleInvalidParameter() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                "The query string could not be read. Check that it is URL-encoded correctly.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception exception) {
        LOG.error("Unexpected error", exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Something went wrong. Please try again later.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        return problemDetail;
    }
}
