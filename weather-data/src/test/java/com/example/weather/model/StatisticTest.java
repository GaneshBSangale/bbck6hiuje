package com.example.weather.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.weather.exception.WeatherApiException;
import org.junit.jupiter.api.Test;

// Statistic is the list of statistics a query can ask for: min, max, sum and avg.
class StatisticTest {

    // SCENARIO: a statistic name is found regardless of letter case and surrounding spaces.
    @Test
    void findsStatisticIgnoringCase() {
        // ARRANGE
        String apiName = " MAX ";

        // ACT
        Statistic statistic = Statistic.fromApiName(apiName);

        // ASSERT
        assertEquals(Statistic.MAX, statistic);
    }

    // SCENARIO: an unknown name is rejected and the message lists the allowed statistics.
    @Test
    void rejectsUnknownStatisticName() {
        // ARRANGE
        String apiName = "median";

        // ACT AND ASSERT
        WeatherApiException exception =
                assertThrows(WeatherApiException.UnknownStatistic.class, () -> Statistic.fromApiName(apiName));

        // ASSERT
        assertEquals("Unknown statistic 'median'. Allowed statistics: min, max, sum, avg.", exception.getMessage());
    }
}
