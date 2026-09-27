# Build the Spring Boot backend with Java 21
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests clean package

# Small Java 21 runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/youtube-watch-party-backend-1.0.0.jar app.jar
EXPOSE 5000
ENTRYPOINT ["java", "-jar", "app.jar"]
