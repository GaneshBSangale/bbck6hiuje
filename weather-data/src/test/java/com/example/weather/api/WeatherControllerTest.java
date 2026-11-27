package com.example.weather.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.weather.api.ApiModels.MetricValue;
import com.example.weather.api.ApiModels.ReadingRequest;
import com.example.weather.api.ApiModels.ReadingResponse;
import com.example.weather.exception.ApiExceptionHandler;
import com.example.weather.exception.WeatherApiException;
import com.example.weather.service.WeatherService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// WeatherController maps the two HTTP endpoints to WeatherService and sets the response status.
class WeatherControllerTest {

    private static final String READING_JSON = """
            {"timestamp": "2026-11-26T10:00:00Z", "metrics": {"temperature": 21.5}}
            """;

    private final WeatherService weatherService = mock(WeatherService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WeatherController(weatherService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    // SCENARIO: posting a sensor report returns 201 with the service's answer as JSON.
    @Test
    void postingReadingsReturnsCreated() throws Exception {
        // ARRANGE
        ReadingResponse serviceResponse = new ReadingResponse(
                "sensor-1", Instant.parse("2026-11-26T10:00:00Z"), Map.of("temperature", new MetricValue(21.5, "°C")));
        when(weatherService.saveReadings(eq("sensor-1"), any(ReadingRequest.class)))
                .thenReturn(serviceResponse);

        // ACT
        ResultActions result = mockMvc.perform(post("/api/v1/sensors/sensor-1/readings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(READING_JSON));

        // ASSERT
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.metrics.temperature.value").value(21.5));
    }

    // SCENARIO: the JSON body reaches the service as a request with the timestamp and metrics.
    @Test
    void postingReadingsPassesTheBodyToTheService() throws Exception {
        // ARRANGE
        ArgumentCaptor<ReadingRequest> requestCaptor = ArgumentCaptor.forClass(ReadingRequest.class);

        // ACT
        ResultActions result = mockMvc.perform(post("/api/v1/sensors/sensor-1/readings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(READING_JSON));

        // ASSERT
        result.andExpect(status().isCreated());
        verify(weatherService).saveReadings(eq("sensor-1"), requestCaptor.capture());
        ReadingRequest request = requestCaptor.getValue();
        assertEquals("2026-11-26T10:00:00Z", request.timestamp());
        assertEquals(Map.of("temperature", 21.5), request.metrics());
    }

    // SCENARIO: comma-separated query parameters reach the service as lists, with the other values unchanged.
    @Test
    void statsQueryPassesEveryParameterToTheService() throws Exception {
        // ARRANGE
        String query = "/api/v1/stats?sensors=sensor-1,sensor-2&metrics=temperature,humidity&stat=max"
                + "&from=2026-11-19&to=2026-11-25";

        // ACT
        ResultActions result = mockMvc.perform(get(query));

        // ASSERT
        result.andExpect(status().isOk());
        verify(weatherService)
                .getStats(
                        List.of("sensor-1", "sensor-2"),
                        List.of("temperature", "humidity"),
                        "max",
                        "2026-11-19",
                        "2026-11-25");
    }

    // SCENARIO: a query without a statistic asks the service for the average.
    @Test
    void statsQueryDefaultsToAverage() throws Exception {
        // ARRANGE
        String query = "/api/v1/stats?metrics=temperature";

        // ACT
        ResultActions result = mockMvc.perform(get(query));

        // ASSERT
        result.andExpect(status().isOk());
        verify(weatherService).getStats(isNull(), eq(List.of("temperature")), eq("avg"), isNull(), isNull());
    }

    // SCENARIO: a query without the metrics parameter is rejected with 400 before reaching the service.
    @Test
    void statsQueryWithoutMetricsReturnsBadRequest() throws Exception {
        // ARRANGE
        String query = "/api/v1/stats?sensors=sensor-1";

        // ACT
        ResultActions result = mockMvc.perform(get(query));

        // ASSERT
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Bad Request"));
    }

    // SCENARIO: an error raised by the service comes back as a Problem Details body.
    @Test
    void serviceErrorReturnsProblemDetails() throws Exception {
        // ARRANGE
        when(weatherService.getStats(any(), anyList(), anyString(), any(), any()))
                .thenThrow(new WeatherApiException.InvalidDateRange("from must be before to."));

        // ACT
        ResultActions result = mockMvc.perform(get("/api/v1/stats?metrics=temperature"));

        // ASSERT
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid date range"))
                .andExpect(jsonPath("$.detail").value("from must be before to."));
    }
}
