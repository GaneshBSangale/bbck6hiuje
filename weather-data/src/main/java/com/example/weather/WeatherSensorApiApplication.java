package com.example.weather;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Entry point of the weather sensor API.
 * 1. Starts the Spring Boot application.
 * 2. Provides one shared UTC clock for the business rules.
 * 3. Names the API at the top of the Swagger UI page.
 */
@SpringBootApplication
@OpenAPIDefinition(
        info =
                @Info(
                        title = "Weather Sensor API",
                        version = "v1",
                        description = "Receives weather readings from sensors and returns statistics about them."))
public class WeatherSensorApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherSensorApiApplication.class, args);
    }

    // One shared clock, so tests can fix "now" for the default range and the future-time check.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
