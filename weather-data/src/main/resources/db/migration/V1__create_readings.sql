-- Ids come from a sequence in blocks of 50, so Hibernate can batch inserts.
CREATE SEQUENCE readings_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE readings (
    id           BIGINT                   NOT NULL PRIMARY KEY,
    sensor_id    VARCHAR(64)              NOT NULL,
    metric       VARCHAR(32)              NOT NULL,
    metric_value DOUBLE PRECISION         NOT NULL,
    recorded_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- One value per sensor, time and metric. This column order lets the duplicate check (sensor and time)
    -- and per-sensor time ranges seek on the key.
    CONSTRAINT uk_readings_sensor_time_metric UNIQUE (sensor_id, recorded_at, metric)
);
