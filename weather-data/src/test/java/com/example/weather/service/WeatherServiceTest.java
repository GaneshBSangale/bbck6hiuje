package com.example.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.weather.api.ApiModels.MetricValue;
import com.example.weather.api.ApiModels.ReadingRequest;
import com.example.weather.api.ApiModels.ReadingResponse;
import com.example.weather.api.ApiModels.StatsResponse;
import com.example.weather.exception.WeatherApiException;
import com.example.weather.model.Metric;
import com.example.weather.model.Reading;
import com.example.weather.repository.ReadingRepository;
import com.example.weather.repository.ReadingRepository.MetricAggregate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

// WeatherService holds the business rules: it validates and stores sensor reports and computes statistics.
@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final String TEN_AM_TEXT = "2026-11-26T10:00:00Z";
    private static final Instant TEN_AM = Instant.parse(TEN_AM_TEXT);
    private static final List<String> TEMPERATURE_ONLY = List.of("temperature");

    @Mock
    private ReadingRepository readingRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Captor
    private ArgumentCaptor<List<Reading>> savedReadingsCaptor;

    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        weatherService = new WeatherService(readingRepository, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // SCENARIO: a report with two metrics is accepted and saved as two rows in a single batch call.
    @Test
    void savesEveryMetricOfOneReportInOneBatch() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, orderedMetrics("temperature", 21.5, "humidity", 60.0));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(2, response.metrics().size());
        verify(readingRepository).saveAllAndFlush(savedReadingsCaptor.capture());
        List<Reading> savedReadings = savedReadingsCaptor.getValue();
        assertEquals(2, savedReadings.size());
        assertEquals(Metric.TEMPERATURE, savedReadings.get(0).getMetric());
        assertEquals(60.0, savedReadings.get(1).getMetricValue());
        assertEquals("sensor-1", savedReadings.get(1).getSensorId());
        assertEquals(TEN_AM, savedReadings.get(1).getRecordedAt());
    }

    // SCENARIO: metrics are inserted in one fixed order, whatever order the report lists them in.
    @Test
    void insertsMetricsInOneFixedOrder() {
        // ARRANGE
        ReadingRequest request =
                new ReadingRequest(TEN_AM_TEXT, orderedMetrics("pressure", 1013.0, "temperature", 21.5));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(
                List.of("pressure", "temperature"),
                List.copyOf(response.metrics().keySet()));
        verify(readingRepository).saveAllAndFlush(savedReadingsCaptor.capture());
        List<Metric> savedOrder =
                savedReadingsCaptor.getValue().stream().map(Reading::getMetric).toList();
        assertEquals(List.of(Metric.TEMPERATURE, Metric.PRESSURE), savedOrder);
    }

    // SCENARIO: the reply repeats the value exactly as stored, without rounding it.
    @Test
    void returnsTheExactValueWithoutRounding() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", 21.455));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(new MetricValue(21.455, "°C"), response.metrics().get("temperature"));
    }

    // SCENARIO: the response repeats the accepted values and adds each metric's unit.
    @Test
    void returnsSavedValuesWithUnits() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, orderedMetrics("temperature", 21.5, "humidity", 60.0));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals("sensor-1", response.sensorId());
        assertEquals(TEN_AM, response.timestamp());
        assertEquals(new MetricValue(21.5, "°C"), response.metrics().get("temperature"));
        assertEquals(new MetricValue(60.0, "%"), response.metrics().get("humidity"));
    }

    // SCENARIO: a report without a timestamp is stored with the current time.
    @Test
    void usesCurrentTimeWhenTimestampIsMissing() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(null, Map.of("temperature", 21.5));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(NOW, response.timestamp());
    }

    // SCENARIO: a timestamp after the current time is rejected and nothing is saved.
    @Test
    void rejectsTimestampInTheFuture() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(NOW.plusSeconds(1).toString(), Map.of("temperature", 21.5));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals("Timestamp 2026-11-26T12:00:01Z is in the future.", exception.getMessage());
        verify(readingRepository, never()).saveAllAndFlush(any());
    }

    // SCENARIO: a timestamp that is not ISO-8601 text (here an epoch number as text) is rejected.
    @Test
    void rejectsTimestampThatIsNotIsoText() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest("1763000000", Map.of("temperature", 21.5));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals(
                "Timestamp must be an ISO-8601 time like 2026-11-20T10:00:00Z. You sent '1763000000'.",
                exception.getMessage());
    }

    // SCENARIO: a timestamp with an offset is stored as the same moment in UTC.
    @Test
    void storesTimestampWithOffsetInUtc() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest("2026-11-26T11:00:00+01:00", Map.of("temperature", 21.5));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(TEN_AM, response.timestamp());
    }

    // SCENARIO: a sensor id with characters outside letters, digits, '-' and '_' is rejected.
    @Test
    void rejectsSensorIdWithInvalidCharacters() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", 21.5));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor 1!", request));

        // ASSERT
        assertEquals("Sensor id must be 1 to 64 letters, digits, '-' or '_'.", exception.getMessage());
    }

    // SCENARIO: a report with no metrics is rejected.
    @Test
    void rejectsReportWithoutMetrics() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, null);

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals("Send at least one metric, for example {\"temperature\": 21.5}.", exception.getMessage());
    }

    // SCENARIO: a metric name that is not in the fixed list is rejected.
    @Test
    void rejectsUnknownMetric() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("rain", 3.0));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.UnknownMetric.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals(
                "Unknown metric 'rain'. Allowed metrics: temperature, humidity, wind_speed, pressure.",
                exception.getMessage());
    }

    // SCENARIO: a metric sent with a null value is rejected.
    @Test
    void rejectsMetricWithoutValue() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Collections.singletonMap("humidity", null));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals("Value for humidity is missing.", exception.getMessage());
    }

    // SCENARIO: a value outside the metric's realistic limits is rejected with the limits in the message.
    @Test
    void rejectsValueOutsideTheMetricLimits() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("humidity", 120.5));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals("humidity must be from 0 to 100 %. You sent 120.5.", exception.getMessage());
    }

    // SCENARIO: an infinite value (for example 1e400 in JSON) is rejected as an invalid reading, not a server error.
    @Test
    void rejectsInfiniteValue() {
        // ARRANGE
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", Double.POSITIVE_INFINITY));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals("temperature must be a finite number.", exception.getMessage());
    }

    // SCENARIO: the same metric written twice in one report (in any letter case) is rejected.
    @Test
    void rejectsSameMetricTwiceInOneReport() {
        // ARRANGE
        ReadingRequest request =
                new ReadingRequest(TEN_AM_TEXT, orderedMetrics("temperature", 20.0, "Temperature", 21.0));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals("Each metric can appear only once per report.", exception.getMessage());
    }

    // SCENARIO: a metric already stored with the same value is accepted again, but only the new metric is saved.
    @Test
    void skipsMetricAlreadyStoredWithTheSameValue() {
        // ARRANGE
        when(readingRepository.findBySensorIdAndRecordedAt("sensor-1", TEN_AM))
                .thenReturn(List.of(new Reading("sensor-1", Metric.TEMPERATURE, 21.5, TEN_AM)));
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, orderedMetrics("temperature", 21.5, "humidity", 60.0));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(2, response.metrics().size());
        verify(readingRepository).saveAllAndFlush(savedReadingsCaptor.capture());
        List<Reading> savedReadings = savedReadingsCaptor.getValue();
        assertEquals(1, savedReadings.size());
        assertEquals(Metric.HUMIDITY, savedReadings.get(0).getMetric());
    }

    // SCENARIO: -0.0 sent for a stored 0 counts as the same value, so it is not a conflict and nothing new is saved.
    @Test
    void treatsNegativeZeroAsTheStoredZero() {
        // ARRANGE
        when(readingRepository.findBySensorIdAndRecordedAt("sensor-1", TEN_AM))
                .thenReturn(List.of(new Reading("sensor-1", Metric.TEMPERATURE, 0.0, TEN_AM)));
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", -0.0));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(new MetricValue(0.0, "°C"), response.metrics().get("temperature"));
        verify(readingRepository).saveAllAndFlush(savedReadingsCaptor.capture());
        assertEquals(List.of(), savedReadingsCaptor.getValue());
    }

    // SCENARIO: when a parallel request saved the same reading first, one retry treats it as an identical repeat.
    @Test
    void retriesOnceWhenParallelRequestSavedTheSameReading() {
        // ARRANGE
        when(readingRepository.saveAllAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenReturn(List.of());
        when(readingRepository.findBySensorIdAndRecordedAt("sensor-1", TEN_AM))
                .thenReturn(List.of())
                .thenReturn(List.of(new Reading("sensor-1", Metric.TEMPERATURE, 21.5, TEN_AM)));
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", 21.5));

        // ACT
        ReadingResponse response = weatherService.saveReadings("sensor-1", request);

        // ASSERT
        assertEquals(new MetricValue(21.5, "°C"), response.metrics().get("temperature"));
        verify(readingRepository, times(2)).saveAllAndFlush(savedReadingsCaptor.capture());
        assertEquals(List.of(), savedReadingsCaptor.getAllValues().get(1));
    }

    // SCENARIO: when a parallel request saved a different value first, the retry reports a conflict.
    @Test
    void reportsConflictWhenParallelRequestSavedDifferentValue() {
        // ARRANGE
        when(readingRepository.saveAllAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(readingRepository.findBySensorIdAndRecordedAt("sensor-1", TEN_AM))
                .thenReturn(List.of())
                .thenReturn(List.of(new Reading("sensor-1", Metric.TEMPERATURE, 25.0, TEN_AM)));
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", 21.5));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.ConflictingReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals(
                "sensor-1 already has temperature 25 at 2026-11-26T10:00:00Z. A different value (21.5) cannot"
                        + " replace it.",
                exception.getMessage());
    }

    // SCENARIO: if the retry collides again, the database error is passed on for the handler to answer with 409.
    @Test
    void passesOnSecondCollision() {
        // ARRANGE
        when(readingRepository.saveAllAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", 21.5));

        // ACT AND ASSERT
        assertThrows(DataIntegrityViolationException.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        verify(readingRepository, times(2)).saveAllAndFlush(any());
    }

    // SCENARIO: a metric already stored at the same time with a different value is a conflict.
    @Test
    void rejectsDifferentValueForAnAlreadyStoredMetric() {
        // ARRANGE
        when(readingRepository.findBySensorIdAndRecordedAt("sensor-1", TEN_AM))
                .thenReturn(List.of(new Reading("sensor-1", Metric.TEMPERATURE, 21.5, TEN_AM)));
        ReadingRequest request = new ReadingRequest(TEN_AM_TEXT, Map.of("temperature", 25.0));

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.ConflictingReading.class, () -> weatherService.saveReadings("sensor-1", request));

        // ASSERT
        assertEquals(
                "sensor-1 already has temperature 21.5 at 2026-11-26T10:00:00Z. A different value (25) cannot"
                        + " replace it.",
                exception.getMessage());
    }

    // SCENARIO: with no dates, the query covers the 24 hours before now.
    @Test
    void usesLast24HoursWhenNoDatesAreGiven() {
        // ARRANGE
        Instant dayBeforeNow = NOW.minusSeconds(24 * 60 * 60);

        // ACT
        StatsResponse response = weatherService.getStats(null, TEMPERATURE_ONLY, "avg", null, null);

        // ASSERT
        assertEquals(dayBeforeNow, response.from());
        assertEquals(NOW, response.to());
        verify(readingRepository).aggregateAllSensors(List.of(Metric.TEMPERATURE), dayBeforeNow, NOW);
    }

    // SCENARIO: the default range is cut to milliseconds, the same precision as stored readings.
    @Test
    void cutsTheDefaultRangeToMilliseconds() {
        // ARRANGE
        Clock clockWithMicroseconds = Clock.fixed(Instant.parse("2026-11-26T12:00:00.123456Z"), ZoneOffset.UTC);
        WeatherService serviceWithMicroseconds =
                new WeatherService(readingRepository, transactionManager, clockWithMicroseconds);

        // ACT
        StatsResponse response = serviceWithMicroseconds.getStats(null, TEMPERATURE_ONLY, "avg", null, null);

        // ASSERT
        assertEquals(Instant.parse("2026-11-26T12:00:00.123Z"), response.to());
    }

    // SCENARIO: plain dates include the whole "to" day, so the range ends at the following midnight.
    @Test
    void includesTheWholeLastDayForPlainDates() {
        // ARRANGE
        Instant expectedFrom = Instant.parse("2026-11-19T00:00:00Z");
        Instant expectedTo = Instant.parse("2026-11-26T00:00:00Z");

        // ACT
        StatsResponse response = weatherService.getStats(null, TEMPERATURE_ONLY, "avg", "2026-11-19", "2026-11-25");

        // ASSERT
        assertEquals(expectedFrom, response.from());
        assertEquals(expectedTo, response.to());
    }

    // SCENARIO: full timestamps are used exactly as given.
    @Test
    void usesTimestampsExactlyAsGiven() {
        // ARRANGE
        String from = "2026-11-19T06:30:00Z";
        String to = "2026-11-21T06:30:00Z";

        // ACT
        StatsResponse response = weatherService.getStats(null, TEMPERATURE_ONLY, "avg", from, to);

        // ASSERT
        assertEquals(Instant.parse(from), response.from());
        assertEquals(Instant.parse(to), response.to());
    }

    // SCENARIO: giving only one end of the range is rejected.
    @Test
    void rejectsRangeWithOnlyOneEnd() {
        // ARRANGE
        String from = "2026-11-19";

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidDateRange.class,
                () -> weatherService.getStats(null, TEMPERATURE_ONLY, "avg", from, null));

        // ASSERT
        assertEquals("Give both from and to, or neither to get the last 24 hours.", exception.getMessage());
    }

    // SCENARIO: a range shorter than one day is rejected.
    @Test
    void rejectsRangeShorterThanOneDay() {
        // ARRANGE
        String from = "2026-11-19T00:00:00Z";
        String to = "2026-11-19T12:00:00Z";

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidDateRange.class,
                () -> weatherService.getStats(null, TEMPERATURE_ONLY, "avg", from, to));

        // ASSERT
        assertEquals("The date range must be at least 1 day.", exception.getMessage());
    }

    // SCENARIO: a range longer than 31 days is rejected and the message says how long it was.
    @Test
    void rejectsRangeLongerThan31Days() {
        // ARRANGE
        String from = "2026-10-01";
        String to = "2026-11-14";

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidDateRange.class,
                () -> weatherService.getStats(null, TEMPERATURE_ONLY, "avg", from, to));

        // ASSERT
        assertEquals("The date range can be at most 31 days. You asked for 45 days.", exception.getMessage());
    }

    // SCENARIO: a range just over 31 days with a partial day is reported as "more than 31 days", not "31 days".
    @Test
    void describesPartialExtraDayAsMoreThan31Days() {
        // ARRANGE
        String from = "2026-10-01T00:00:00Z";
        String to = "2026-11-01T12:00:00Z";

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidDateRange.class,
                () -> weatherService.getStats(null, TEMPERATURE_ONLY, "avg", from, to));

        // ASSERT
        assertEquals("The date range can be at most 31 days. You asked for more than 31 days.", exception.getMessage());
    }

    // SCENARIO: a range whose start is after its end is rejected.
    @Test
    void rejectsRangeThatEndsBeforeItStarts() {
        // ARRANGE
        String from = "2026-11-25T00:00:00Z";
        String to = "2026-11-19T00:00:00Z";

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidDateRange.class,
                () -> weatherService.getStats(null, TEMPERATURE_ONLY, "avg", from, to));

        // ASSERT
        assertEquals("from must be before to.", exception.getMessage());
    }

    // SCENARIO: a date in an unreadable format is rejected with an example of the right format.
    @Test
    void rejectsUnreadableDate() {
        // ARRANGE
        String from = "19/11/2026";

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.InvalidDateRange.class,
                () -> weatherService.getStats(null, TEMPERATURE_ONLY, "avg", from, "2026-11-25"));

        // ASSERT
        assertEquals(
                "from must be a date like 2026-11-19 or a timestamp like 2026-11-19T10:00:00Z. You sent"
                        + " '19/11/2026'.",
                exception.getMessage());
    }

    // SCENARIO: a statistic outside min, max, sum and avg is rejected.
    @Test
    void rejectsUnknownStatistic() {
        // ARRANGE
        String stat = "median";

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.UnknownStatistic.class,
                () -> weatherService.getStats(null, TEMPERATURE_ONLY, stat, null, null));

        // ASSERT
        assertEquals("Unknown statistic 'median'. Allowed statistics: min, max, sum, avg.", exception.getMessage());
    }

    // SCENARIO: a query whose metric list is blank is rejected.
    @Test
    void rejectsQueryWithoutMetrics() {
        // ARRANGE
        List<String> blankMetrics = List.of(" ");

        // ACT AND ASSERT
        WeatherApiException exception = assertThrows(
                WeatherApiException.UnknownMetric.class,
                () -> weatherService.getStats(null, blankMetrics, "avg", null, null));

        // ASSERT
        assertEquals(
                "Choose at least one metric: temperature, humidity, wind_speed, pressure.", exception.getMessage());
    }

    // SCENARIO: when sensors are named, only those sensors are queried, trimmed and without repeats.
    @Test
    void queriesOnlyTheNamedSensors() {
        // ARRANGE
        List<String> sensors = List.of(" sensor-1", "sensor-2", "sensor-1", "");
        Instant dayBeforeNow = NOW.minusSeconds(24 * 60 * 60);

        // ACT
        StatsResponse response = weatherService.getStats(sensors, TEMPERATURE_ONLY, "avg", null, null);

        // ASSERT
        assertEquals(List.of(), response.sensors());
        verify(readingRepository)
                .aggregateSensors(List.of("sensor-1", "sensor-2"), List.of(Metric.TEMPERATURE), dayBeforeNow, NOW);
    }

    // SCENARIO: the response holds one entry per sensor, each with the requested statistic per metric.
    @ParameterizedTest
    @CsvSource({"min, 10.0", "max, 30.0", "sum, 40.0", "avg, 20.0"})
    void returnsTheRequestedStatisticPerSensor(String stat, double expectedValue) {
        // ARRANGE
        Instant dayBeforeNow = NOW.minusSeconds(24 * 60 * 60);
        List<Metric> metrics = List.of(Metric.TEMPERATURE, Metric.HUMIDITY);
        when(readingRepository.aggregateAllSensors(metrics, dayBeforeNow, NOW))
                .thenReturn(List.of(
                        new AggregateRow("sensor-1", Metric.HUMIDITY, 10, 30, 40, 20),
                        new AggregateRow("sensor-1", Metric.TEMPERATURE, 10, 30, 40, 20),
                        new AggregateRow("sensor-2", Metric.TEMPERATURE, 10, 30, 40, 20)));

        // ACT
        StatsResponse response = weatherService.getStats(null, List.of("temperature", "humidity"), stat, null, null);

        // ASSERT
        assertEquals(stat, response.stat());
        assertEquals(2, response.sensors().size());
        assertEquals(
                new MetricValue(expectedValue, "%"),
                response.sensors().get(0).metrics().get("humidity"));
        assertEquals("sensor-2", response.sensors().get(1).sensorId());
    }

    private static Map<String, Double> orderedMetrics(
            String firstName, double firstValue, String secondName, double secondValue) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put(firstName, firstValue);
        metrics.put(secondName, secondValue);
        return metrics;
    }

    // Record components are named after the projection getters, so the record implements the interface directly.
    private record AggregateRow(
            String getSensorId,
            Metric getMetric,
            double getMinValue,
            double getMaxValue,
            double getSumValue,
            double getAvgValue)
            implements MetricAggregate {}
}
