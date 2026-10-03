FROM docker.io/library/maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app
COPY pom.xml .

RUN mvn dependency:go-offline -q

COPY src/ ./src/
RUN mvn package -DskipTests -q

FROM docker.io/library/eclipse-temurin:25-jre

RUN useradd --system --create-home --uid 10001 appuser

WORKDIR /app

COPY --from=build /app/target/app.jar app.jar
COPY docker/start.sh /start.sh
RUN chmod +x /start.sh && chown appuser:appuser /app/app.jar

USER appuser

EXPOSE 8945

CMD ["/start.sh"]
