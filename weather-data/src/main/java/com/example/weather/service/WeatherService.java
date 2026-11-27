package com.example.weather.service;

import com.example.weather.api.ApiModels.MetricValue;
import com.example.weather.api.ApiModels.ReadingRequest;
import com.example.weather.api.ApiModels.ReadingResponse;
import com.example.weather.api.ApiModels.SensorStats;
import com.example.weather.api.ApiModels.StatsResponse;
import com.example.weather.exception.WeatherApiException;
import com.example.weather.model.Metric;
import com.example.weather.model.Reading;
import com.example.weather.model.Statistic;
import com.example.weather.repository.ReadingRepository;
import com.example.weather.repository.ReadingRepository.MetricAggregate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Business rules for weather readings.
 * 1. saveReadings validates one sensor report and stores each metric as a row, in one batch.
 * 2. Sending the same reading again stores nothing new; the same time with a different value is a conflict.
 * 3. getStats validates a query, works out the date range and returns one statistic per sensor and metric.
 */
@Service
public class WeatherService {

    private static final Logger LOG = LoggerFactory.getLogger(WeatherService.class);
    private static final Pattern SENSOR_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Duration DEFAULT_RANGE = Duration.ofDays(1);
    private static final Duration MIN_RANGE = Duration.ofDays(1);
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final int PLAIN_DATE_LENGTH = "2026-01-01".length();

    private final ReadingRepository readingRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public WeatherService(
            ReadingRepository readingRepository, PlatformTransactionManager transactionManager, Clock clock) {
        this.readingRepository = readingRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Parent of validateSensorId, resolveTimestamp, parseTimestamp, validateMetrics, validateValue, storeNewReadings,
    // insertNewReadings and rejectConflicts.
    public ReadingResponse saveReadings(String sensorId, ReadingRequest request) {
        logStep(1, "validate the sensor report");
        validateSensorId(sensorId);
        Instant recordedAt = resolveTimestamp(request.timestamp());
        Map<Metric, Double> values = validateMetrics(request.metrics());

        logStep(2, "store new metrics for " + sensorId + " at " + recordedAt);
        storeNewReadings(sensorId, recordedAt, values);
        return new ReadingResponse(sensorId, recordedAt, toMetricValues(values));
    }

    private static void validateSensorId(String sensorId) {
        if (!SENSOR_ID_PATTERN.matcher(sensorId).matches()) {
            throw new WeatherApiException.InvalidReading("Sensor id must be 1 to 64 letters, digits, '-' or '_'.");
        }
    }

    // Millisecond precision is plenty for weather data and makes repeated timestamps compare exactly.
    private Instant resolveTimestamp(String timestamp) {
        Instant now = Instant.now(clock);
        if (timestamp == null) {
            return now.truncatedTo(ChronoUnit.MILLIS);
        }
        Instant recordedAt = parseTimestamp(timestamp);
        if (recordedAt.isAfter(now)) {
            throw new WeatherApiException.InvalidReading("Timestamp " + recordedAt + " is in the future.");
        }
        return recordedAt.truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp.trim());
        } catch (DateTimeParseException _) {
            throw new WeatherApiException.InvalidReading(
                    "Timestamp must be an ISO-8601 time like 2026-11-20T10:00:00Z. You sent '" + timestamp + "'.");
        }
    }

    private static Map<Metric, Double> validateMetrics(Map<String, Double> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            throw new WeatherApiException.InvalidReading(
                    "Send at least one metric, for example {\"temperature\": 21.5}.");
        }
        Map<Metric, Double> values = new LinkedHashMap<>();
        metrics.forEach((name, value) -> {
            Metric metric = Metric.fromApiName(name);
            if (values.containsKey(metric)) {
                throw new WeatherApiException.InvalidReading("Each metric can appear only once per report.");
            }
            values.put(metric, validateValue(metric, value));
        });
        return values;
    }

    // Adding 0.0 turns -0.0 into 0.0, so both ways of writing zero count as the same stored value.
    private static double validateValue(Metric metric, Double value) {
        if (value == null) {
            throw new WeatherApiException.InvalidReading("Value for " + metric.apiName() + " is missing.");
        }
        if (!Double.isFinite(value)) {
            throw new WeatherApiException.InvalidReading(metric.apiName() + " must be a finite number.");
        }
        if (!metric.accepts(value)) {
            throw new WeatherApiException.InvalidReading(metric.apiName() + " must be from " + metric.describeLimits()
                    + ". You sent " + formatNumber(value) + ".");
        }
        return value + 0.0;
    }

    // Two identical reports racing each other can both pass the duplicate check. The unique key stops the second
    // insert, and one retry then sees the first report's rows and applies the normal duplicate rules.
    private void storeNewReadings(String sensorId, Instant recordedAt, Map<Metric, Double> values) {
        try {
            transactionTemplate.executeWithoutResult(_ -> insertNewReadings(sensorId, recordedAt, values));
        } catch (DataIntegrityViolationException _) {
            LOG.warn("Retrying readings of {} at {} after a unique-key collision", sensorId, recordedAt);
            transactionTemplate.executeWithoutResult(_ -> insertNewReadings(sensorId, recordedAt, values));
        }
    }

    private void insertNewReadings(String sensorId, Instant recordedAt, Map<Metric, Double> values) {
        Map<Metric, Double> storedValues = readingRepository.findBySensorIdAndRecordedAt(sensorId, recordedAt).stream()
                .collect(Collectors.toMap(Reading::getMetric, Reading::getMetricValue));
        rejectConflicts(sensorId, recordedAt, values, storedValues);
        // One fixed metric order means two reports listing metrics differently cannot lock each other's rows.
        List<Reading> newReadings = values.entrySet().stream()
                .filter(entry -> !storedValues.containsKey(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Reading(sensorId, entry.getKey(), entry.getValue(), recordedAt))
                .toList();
        logStep(3, "save " + newReadings.size() + " new readings in one batch");
        readingRepository.saveAllAndFlush(newReadings);
    }

    private static void rejectConflicts(
            String sensorId, Instant recordedAt, Map<Metric, Double> values, Map<Metric, Double> storedValues) {
        values.forEach((metric, value) -> {
            Double storedValue = storedValues.get(metric);
            if (storedValue != null && Double.compare(storedValue, value) != 0) {
                throw new WeatherApiException.ConflictingReading(sensorId + " already has " + metric.apiName() + " "
                        + formatNumber(storedValue) + " at " + recordedAt + ". A different value ("
                        + formatNumber(value) + ") cannot replace it.");
            }
        });
    }

    // Parent of resolveSensorIds, resolveMetrics, resolveRange, parseBoundary, validateSpan, describeDays, aggregate
    // and toStatsResponse.
    @Transactional(readOnly = true)
    public StatsResponse getStats(List<String> sensors, List<String> metrics, String stat, String from, String to) {
        logStep(1, "validate the query");
        StatsQuery query = new StatsQuery(
                resolveSensorIds(sensors),
                resolveMetrics(metrics),
                Statistic.fromApiName(stat),
                resolveRange(from, to));

        logStep(
                2,
                "aggregate " + query.statistic().apiName() + " from "
                        + query.range().from() + " to " + query.range().to());
        List<MetricAggregate> aggregates = aggregate(query);

        logStep(3, "group " + aggregates.size() + " results by sensor");
        return toStatsResponse(query, aggregates);
    }

    // No sensors in the query means all sensors.
    private static List<String> resolveSensorIds(List<String> sensors) {
        if (sensors == null) {
            return List.of();
        }
        return sensors.stream()
                .map(String::trim)
                .filter(sensorId -> !sensorId.isEmpty())
                .distinct()
                .toList();
    }

    private static List<Metric> resolveMetrics(List<String> metrics) {
        List<Metric> requestedMetrics = metrics.stream()
                .filter(name -> !name.isBlank())
                .map(Metric::fromApiName)
                .distinct()
                .toList();
        if (requestedMetrics.isEmpty()) {
            throw new WeatherApiException.UnknownMetric("Choose at least one metric: " + Metric.allowedNames() + ".");
        }
        return requestedMetrics;
    }

    private DateRange resolveRange(String from, String to) {
        boolean hasFrom = from != null && !from.isBlank();
        boolean hasTo = to != null && !to.isBlank();
        if (!hasFrom && !hasTo) {
            Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
            return new DateRange(now.minus(DEFAULT_RANGE), now);
        }
        if (!hasFrom || !hasTo) {
            throw new WeatherApiException.InvalidDateRange(
                    "Give both from and to, or neither to get the last 24 hours.");
        }
        DateRange range = new DateRange(parseBoundary("from", from, 0), parseBoundary("to", to, 1));
        validateSpan(range);
        return range;
    }

    // A plain "to" date includes that whole day, so it moves to the next midnight (plainDateExtraDays = 1).
    private static Instant parseBoundary(String name, String text, int plainDateExtraDays) {
        String trimmedText = text.trim();
        try {
            if (trimmedText.length() == PLAIN_DATE_LENGTH) {
                return LocalDate.parse(trimmedText)
                        .plusDays(plainDateExtraDays)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
            }
            return Instant.parse(trimmedText);
        } catch (DateTimeParseException _) {
            throw new WeatherApiException.InvalidDateRange(name
                    + " must be a date like 2026-11-19 or a timestamp like 2026-11-19T10:00:00Z. You sent '"
                    + trimmedText + "'.");
        }
    }

    private static void validateSpan(DateRange range) {
        if (!range.from().isBefore(range.to())) {
            throw new WeatherApiException.InvalidDateRange("from must be before to.");
        }
        Duration span = Duration.between(range.from(), range.to());
        if (span.compareTo(MIN_RANGE) < 0) {
            throw new WeatherApiException.InvalidDateRange("The date range must be at least 1 day.");
        }
        if (span.compareTo(MAX_RANGE) > 0) {
            throw new WeatherApiException.InvalidDateRange(
                    "The date range can be at most 31 days. You asked for " + describeDays(span) + ".");
        }
    }

    // "31 days" would be wrong for 31.5 days, so a partial day is reported as "more than".
    private static String describeDays(Duration span) {
        long wholeDays = span.toDays();
        return Duration.ofDays(wholeDays).equals(span) ? wholeDays + " days" : "more than " + wholeDays + " days";
    }

    private List<MetricAggregate> aggregate(StatsQuery query) {
        DateRange range = query.range();
        if (query.sensorIds().isEmpty()) {
            return readingRepository.aggregateAllSensors(query.metrics(), range.from(), range.to());
        }
        return readingRepository.aggregateSensors(query.sensorIds(), query.metrics(), range.from(), range.to());
    }

    private static StatsResponse toStatsResponse(StatsQuery query, List<MetricAggregate> aggregates) {
        Map<String, Map<String, MetricValue>> metricsBySensor = new LinkedHashMap<>();
        aggregates.forEach(aggregate -> metricsBySensor
                .computeIfAbsent(aggregate.getSensorId(), _ -> new LinkedHashMap<>())
                .put(
                        aggregate.getMetric().apiName(),
                        MetricValue.of(aggregate.getMetric(), pickValue(query.statistic(), aggregate))));
        List<SensorStats> sensors = metricsBySensor.entrySet().stream()
                .map(entry -> new SensorStats(entry.getKey(), entry.getValue()))
                .toList();
        return new StatsResponse(
                query.statistic().apiName(), query.range().from(), query.range().to(), sensors);
    }

    private static double pickValue(Statistic statistic, MetricAggregate aggregate) {
        return switch (statistic) {
            case MIN -> aggregate.getMinValue();
            case MAX -> aggregate.getMaxValue();
            case SUM -> aggregate.getSumValue();
            case AVG -> aggregate.getAvgValue();
        };
    }

    // The reply repeats the stored values exactly; only statistics are rounded.
    private static Map<String, MetricValue> toMetricValues(Map<Metric, Double> values) {
        Map<String, MetricValue> metricValues = new LinkedHashMap<>();
        values.forEach((metric, value) -> metricValues.put(metric.apiName(), new MetricValue(value, metric.getUnit())));
        return metricValues;
    }

    private static String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void logStep(int step, String description) {
        LOG.info("STEP {}: {}", step, description);
    }

    private record DateRange(Instant from, Instant to) {}

    private record StatsQuery(List<String> sensorIds, List<Metric> metrics, Statistic statistic, DateRange range) {}
}
