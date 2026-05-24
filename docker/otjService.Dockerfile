# Stage 1 — build
FROM eclipse-temurin:25-jdk AS build

RUN apt-get update -q && apt-get install -y -q maven && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pom.xml .

# Download dependencies as a separate layer — only re-runs if pom.xml changes
RUN mvn dependency:go-offline -q

COPY src/ ./src/
RUN mvn package -DskipTests -q

# Stage 2 — Tailscale binaries
FROM tailscale/tailscale:latest AS tailscale

# Stage 3 — runtime
FROM eclipse-temurin:25-jre

RUN apt-get update -q && apt-get install -y -q curl jq && rm -rf /var/lib/apt/lists/*

COPY --from=tailscale /usr/local/bin/tailscale /usr/local/bin/tailscale
COPY --from=tailscale /usr/local/bin/tailscaled /usr/local/bin/tailscaled

WORKDIR /app

COPY --from=build /app/target/app.jar app.jar
COPY docker/start.sh /start.sh
RUN chmod +x /start.sh

EXPOSE 8945

CMD ["/start.sh"]