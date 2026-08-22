# Stage 1 — build
FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app
COPY pom.xml .

# Download dependencies as a separate layer — only re-runs if pom.xml changes
RUN mvn dependency:go-offline -q

COPY src/ ./src/
RUN mvn package -DskipTests -q

# Stage 2 — runtime
FROM docker.io/library/eclipse-temurin:25-jre

# curl is here for exactly one caller: the `HealthCmd=` line in each Quadlet template under
# deploy/prod/. Do not remove it as an unused dependency. The base image ships no HTTP client at
# all — no curl, no wget, no nc — so without this the healthcheck runs `curl -f ...`, exits 127
# with "curl: not found", and every container reports `unhealthy` forever while serving traffic
# perfectly well. That failure is silent in the way that matters: `deploy.sh` health-checks from
# the *host*, so deploys still go green, and only `podman ps` shows the rot.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 appuser

WORKDIR /app

COPY --from=build /app/target/app.jar app.jar
COPY docker/start.sh /start.sh
RUN chmod +x /start.sh && chown appuser:appuser /app/app.jar

USER appuser

EXPOSE 8945

CMD ["/start.sh"]
