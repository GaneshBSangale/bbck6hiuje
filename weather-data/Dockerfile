# Build stage: compiles the app with the Maven Wrapper and a full JDK.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN ./mvnw --batch-mode package -DskipTests

# Run stage: only the Java runtime and the app jar, running as a non-root user.
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 app && mkdir data && chown app data
COPY --from=build /app/target/weather-sensor-api-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
