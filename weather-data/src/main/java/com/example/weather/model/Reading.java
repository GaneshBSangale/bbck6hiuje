package com.example.weather.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One stored value: which sensor, which metric, the value and when it was measured.
 * 1. One row per metric keeps every statistic a simple GROUP BY.
 * 2. Sequence ids come in blocks of 50 (the JPA default, matching V1), so Hibernate can batch inserts.
 */
@Entity
@Table(name = "readings")
public class Reading {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "readings_seq")
    @SequenceGenerator(name = "readings_seq", sequenceName = "readings_seq")
    private Long id;

    @Column(name = "sensor_id", nullable = false, length = 64)
    private String sensorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false, length = 32)
    private Metric metric;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected Reading() {
        // Required by JPA.
    }

    public Reading(String sensorId, Metric metric, double metricValue, Instant recordedAt) {
        this.sensorId = sensorId;
        this.metric = metric;
        this.metricValue = metricValue;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSensorId() {
        return sensorId;
    }

    public Metric getMetric() {
        return metric;
    }

    public double getMetricValue() {
        return metricValue;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
