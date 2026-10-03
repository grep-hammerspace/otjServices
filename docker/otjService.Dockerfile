FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app
COPY pom.xml .

RUN mvn dependency:go-offline -q

COPY src/ ./src/
RUN mvn package -DskipTests -q

FROM docker.io/library/eclipse-temurin:25-jre

# curl is for the Quadlets' HealthCmd; the base image has no HTTP client. Don't remove it as unused.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 appuser

WORKDIR /app

COPY --from=build /app/target/app.jar app.jar
COPY docker/start.sh /start.sh

# This release's box config: otj-converge reads it out of the image on the box; it never runs here.
COPY deploy/ansible/ /deploy/ansible/
COPY deploy/bin/ /deploy/bin/
COPY deploy/haproxy/ /deploy/haproxy/
RUN chmod +x /start.sh && chown appuser:appuser /app/app.jar

USER appuser

EXPOSE 8945

CMD ["/start.sh"]
