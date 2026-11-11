FROM scratch AS artifact
COPY target/documind-backend-0.0.1-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 documind \
    && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home documind \
    && mkdir -p /app/uploads && chown 10001:10001 /app/uploads
WORKDIR /app
COPY --from=artifact --chown=0:0 /app.jar /app/app.jar
USER 10001:10001
ENV SERVER_PORT=8080 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl --fail --silent "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
