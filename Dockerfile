# syntax=docker/dockerfile:1.7
# The platform packages (parkview-platform, GitHub Packages) need credentials: build with
#   docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml .   (server id `github`, see docs/consuming.md of parkview-platform)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -DskipTests package \
    && cp target/ruleengine-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app
WORKDIR /home/app
COPY --from=build --chown=app:app /app.jar app.jar
USER app
ENV PORT=8081
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD curl -fsS "http://localhost:${PORT}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
