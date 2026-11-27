package com.example.weather.repository;

import com.example.weather.model.Metric;
import com.example.weather.model.Reading;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Database access for readings.
 * 1. Statistics are computed by the database in one grouped query, so no rows are loaded into memory.
 * 2. The query returns min, max, sum and avg together; the service picks the one that was asked for.
 */
public interface ReadingRepository extends JpaRepository<Reading, Long> {

    List<Reading> findBySensorIdAndRecordedAt(String sensorId, Instant recordedAt);

    @Query("""
            SELECT r.sensorId AS sensorId, r.metric AS metric,
                   MIN(r.metricValue) AS minValue, MAX(r.metricValue) AS maxValue,
                   SUM(r.metricValue) AS sumValue, AVG(r.metricValue) AS avgValue
            FROM Reading r
            WHERE r.metric IN :metrics AND r.recordedAt >= :rangeStart AND r.recordedAt < :rangeEnd
            GROUP BY r.sensorId, r.metric
            ORDER BY r.sensorId, r.metric
            """)
    List<MetricAggregate> aggregateAllSensors(Collection<Metric> metrics, Instant rangeStart, Instant rangeEnd);

    // Written out in full, like the query above, so each JPQL text can be read and checked as one piece.
    @Query("""
            SELECT r.sensorId AS sensorId, r.metric AS metric,
                   MIN(r.metricValue) AS minValue, MAX(r.metricValue) AS maxValue,
                   SUM(r.metricValue) AS sumValue, AVG(r.metricValue) AS avgValue
            FROM Reading r
            WHERE r.sensorId IN :sensorIds
              AND r.metric IN :metrics AND r.recordedAt >= :rangeStart AND r.recordedAt < :rangeEnd
            GROUP BY r.sensorId, r.metric
            ORDER BY r.sensorId, r.metric
            """)
    List<MetricAggregate> aggregateSensors(
            Collection<String> sensorIds, Collection<Metric> metrics, Instant rangeStart, Instant rangeEnd);

    // All four statistics come back together, so one query serves every value of "stat".
    interface MetricAggregate {
        String getSensorId();

        Metric getMetric();

        double getMinValue();

        double getMaxValue();

        double getSumValue();

        double getAvgValue();
    }
}
