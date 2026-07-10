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

RUN useradd --system --create-home --uid 10001 appuser

WORKDIR /app

COPY --from=build /app/target/app.jar app.jar
COPY docker/start.sh /start.sh
RUN chmod +x /start.sh && chown appuser:appuser /app/app.jar

USER appuser

EXPOSE 8945

CMD ["/start.sh"]
