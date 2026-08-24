# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src

# No BuildKit-only syntax here (no cache mounts): BuildKit proved unstable on the
# development machine, so this file has to build under the classic builder too
# (DOCKER_BUILDKIT=0). A dependency:go-offline step was also tried and removed — it
# pulls plugin artefacts for every lifecycle phase and cost minutes on a cold build.
#
# Tests are skipped here on purpose. They run on the host via `mvn verify`, where the
# Testcontainers integration tests can reach a Docker daemon — this build cannot.
RUN mvn -B clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl is required by the compose healthcheck.
RUN apk add --no-cache curl

# Run as a non-root user: a container process should not be root by default.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
