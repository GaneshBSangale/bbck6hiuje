package com.example.weather.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.weather.model.Reading;
import com.example.weather.repository.ReadingRepository;
import com.jayway.jsonpath.JsonPath;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;

// Step definitions that call the running API over real HTTP and check its JSON responses.
public class WeatherApiSteps {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    private static final Set<String> NON_METRIC_COLUMNS = Set.of("sensor", "timestamp");

    private final ReadingRepository readingRepository;
    private final int port;
    private HttpResponse<String> lastResponse;
    private List<HttpResponse<String>> parallelResponses = List.of();

    public WeatherApiSteps(ReadingRepository readingRepository, @Value("${local.server.port}") int port) {
        this.readingRepository = readingRepository;
        this.port = port;
    }

    // Each scenario starts with an empty table, so scenarios cannot affect each other.
    @Before
    public void clearReadings() {
        readingRepository.deleteAllInBatch();
    }

    @Given("these readings were sent:")
    public void theseReadingsWereSent(List<Map<String, String>> rows) {
        rows.forEach(row -> assertCreated(postReading(row.get("sensor"), toReadingJson(row))));
    }

    @Given("sensor {string} sent {word} {double} {int} hours ago")
    public void sensorSentHoursAgo(String sensorId, String metric, double value, int hoursAgo) {
        Instant timestamp = Instant.now().minus(hoursAgo, ChronoUnit.HOURS);
        String body = "{\"timestamp\": \"" + timestamp + "\", \"metrics\": {\"" + metric + "\": " + value + "}}";
        assertCreated(postReading(sensorId, body));
    }

    @When("sensor {string} sends:")
    public void sensorSends(String sensorId, String body) {
        lastResponse = postReading(sensorId, body);
    }

    // The padding sits inside the JSON (an ignored field), so the parser really reads every byte.
    @When("sensor {string} sends a report padded to {int} bytes")
    public void sensorSendsPaddedReport(String sensorId, int bytes) {
        String prefix = "{\"padding\": \"";
        String suffix = "\", \"metrics\": {\"temperature\": 20}}";
        String padding = "x".repeat(Math.max(0, bytes - prefix.length() - suffix.length()));
        lastResponse = postReading(sensorId, prefix + padding + suffix);
    }

    // All requests are started before any answer is awaited, so they really overlap.
    @When("sensor {string} sends this reading {int} times at once:")
    public void sensorSendsThisReadingTimesAtOnce(String sensorId, int times, String body) {
        List<CompletableFuture<HttpResponse<String>>> pendingResponses = IntStream.range(0, times)
                .mapToObj(_ -> HTTP_CLIENT.sendAsync(readingRequest(sensorId, body), BodyHandlers.ofString()))
                .toList();
        parallelResponses =
                pendingResponses.stream().map(WeatherApiSteps::await).toList();
    }

    @When("I request {string}")
    public void iRequest(String pathAndQuery) {
        lastResponse = send(request(pathAndQuery).GET().build());
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int expectedStatus) {
        assertEquals(expectedStatus, lastResponse.statusCode(), lastResponse.body());
    }

    @Then("every response status is {int}")
    public void everyResponseStatusIs(int expectedStatus) {
        parallelResponses.forEach(response -> assertEquals(expectedStatus, response.statusCode(), response.body()));
    }

    // Counting distinct database ids proves how many rows really exist, independent of the statistics query.
    @Then("sensor {string} has {int} stored reading(s)")
    public void sensorHasStoredReadings(String sensorId, int expectedCount) {
        long storedCount = readingRepository.findAll().stream()
                .filter(reading -> sensorId.equals(reading.getSensorId()))
                .map(Reading::getId)
                .distinct()
                .count();
        assertEquals(expectedCount, storedCount);
    }

    @Then("the response field {string} is {string}")
    public void theResponseFieldIs(String field, String expectedValue) {
        Object actualValue = JsonPath.read(lastResponse.body(), "$." + field);
        assertEquals(expectedValue, String.valueOf(actualValue), lastResponse.body());
    }

    private static String toReadingJson(Map<String, String> row) {
        String metrics = row.entrySet().stream()
                .filter(entry -> !NON_METRIC_COLUMNS.contains(entry.getKey()) && entry.getValue() != null)
                .map(entry -> "\"" + entry.getKey() + "\": " + entry.getValue())
                .collect(Collectors.joining(", "));
        return "{\"timestamp\": \"" + row.get("timestamp") + "\", \"metrics\": {" + metrics + "}}";
    }

    private static void assertCreated(HttpResponse<String> response) {
        assertEquals(201, response.statusCode(), response.body());
    }

    private HttpResponse<String> postReading(String sensorId, String jsonBody) {
        return send(readingRequest(sensorId, jsonBody));
    }

    private HttpRequest readingRequest(String sensorId, String jsonBody) {
        return request("/api/v1/sensors/" + sensorId + "/readings")
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(jsonBody))
                .build();
    }

    private HttpRequest.Builder request(String pathAndQuery) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .timeout(HTTP_TIMEOUT);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP_CLIENT.send(request, BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the API", exception);
        }
    }

    private static HttpResponse<String> await(CompletableFuture<HttpResponse<String>> pendingResponse) {
        try {
            return pendingResponse.get(HTTP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the API", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("A parallel request to the API failed", exception);
        }
    }
}
