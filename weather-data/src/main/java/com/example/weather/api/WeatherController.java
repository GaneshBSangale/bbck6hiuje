package com.example.weather.api;

import com.example.weather.api.ApiModels.ReadingRequest;
import com.example.weather.api.ApiModels.ReadingResponse;
import com.example.weather.api.ApiModels.StatsResponse;
import com.example.weather.service.WeatherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two API endpoints.
 * 1. POST /api/v1/sensors/{sensorId}/readings stores one sensor report.
 * 2. GET /api/v1/stats returns one statistic per sensor and metric for a date range.
 * 3. The OpenAPI annotations give Swagger UI real examples, including the main errors.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Weather", description = "Send sensor readings and query statistics")
public class WeatherController {

    private static final String DEFAULT_STAT = "avg";
    private static final String INVALID_READING_EXAMPLE = """
            {
              "detail": "humidity must be from 0 to 100 %. You sent 120.",
              "status": 400,
              "title": "Invalid reading"
            }
            """;
    private static final String CONFLICTING_READING_EXAMPLE = """
            {
              "detail": "sensor-1 already has temperature 21.5 at 2026-11-20T10:00:00Z. A different value (25) cannot replace it.",
              "status": 409,
              "title": "Conflicting reading"
            }
            """;
    private static final String INVALID_RANGE_EXAMPLE = """
            {
              "detail": "The date range can be at most 31 days. You asked for 45 days.",
              "status": 400,
              "title": "Invalid date range"
            }
            """;

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @PostMapping("/sensors/{sensorId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Send one sensor report",
            description = "Stores the metric values one sensor measured at one time. Sending the same report again"
                    + " stores nothing new.")
    @ApiResponse(responseCode = "201", description = "Report stored; the values come back with their units")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid report, for example a value outside the metric's limits",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            examples = @ExampleObject(value = INVALID_READING_EXAMPLE)))
    @ApiResponse(
            responseCode = "409",
            description = "A different value is already stored for this sensor, metric and time",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            examples = @ExampleObject(value = CONFLICTING_READING_EXAMPLE)))
    public ReadingResponse addReadings(
            @Parameter(description = "Sensor id: 1 to 64 letters, digits, '-' or '_'", example = "sensor-1")
                    @PathVariable
                    String sensorId,
            @RequestBody ReadingRequest request) {
        return weatherService.saveReadings(sensorId, request);
    }

    // The list parameters are documented as plain text, so Swagger UI offers one box for "temperature,humidity".
    @GetMapping("/stats")
    @Operation(
            summary = "Get one statistic per sensor and metric",
            description = "The example asks for the average temperature and humidity of sensor-1 from 19 to 25"
                    + " November.")
    @ApiResponse(responseCode = "200", description = "One entry per sensor; values are rounded to two decimals")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid query, for example a date range longer than 31 days",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            examples = @ExampleObject(value = INVALID_RANGE_EXAMPLE)))
    public StatsResponse getStats(
            @Parameter(
                            description = "Comma-separated sensor ids. Leave empty for all sensors.",
                            example = "sensor-1",
                            schema = @Schema(type = "string"))
                    @RequestParam(required = false)
                    List<String> sensors,
            @Parameter(
                            description = "Comma-separated metrics: temperature, humidity, wind_speed, pressure.",
                            example = "temperature,humidity",
                            schema = @Schema(type = "string"))
                    @RequestParam
                    List<String> metrics,
            @Parameter(
                            description = "The statistic to compute.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            allowableValues = {"min", "max", "sum", "avg"},
                                            defaultValue = DEFAULT_STAT))
                    @RequestParam(defaultValue = DEFAULT_STAT)
                    String stat,
            @Parameter(
                            description = "Start: a date like 2026-11-19 or an ISO-8601 time. Give both from and to,"
                                    + " or neither for the last 24 hours.",
                            example = "2026-11-19")
                    @RequestParam(required = false)
                    String from,
            @Parameter(
                            description = "End: a date (that whole day is included) or an ISO-8601 time (excluded)."
                                    + " The range is 1 to 31 days.",
                            example = "2026-11-25")
                    @RequestParam(required = false)
                    String to) {
        return weatherService.getStats(sensors, metrics, stat, from, to);
    }
}
