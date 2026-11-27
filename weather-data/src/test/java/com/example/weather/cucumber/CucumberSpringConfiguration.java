package com.example.weather.cucumber;

import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

// Starts the whole application on a random port, with an in-memory database, for the Cucumber scenarios.
@CucumberContextConfiguration
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:cucumber;DB_CLOSE_DELAY=-1")
public class CucumberSpringConfiguration {}
