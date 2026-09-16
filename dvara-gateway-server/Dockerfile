FROM eclipse-temurin:25-jre-jammy AS extract
WORKDIR /app
ARG JAR_FILE=app.jar
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r dvara && useradd -r -g dvara -d /app dvara

COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

RUN mkdir -p /var/lib/dvara && chown -R dvara:dvara /app /var/lib/dvara

USER dvara

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD sh -c 'if [ -n "$DVARA_ACTUATOR_API_KEY" ]; then \
                 curl -sf -H "Authorization: Bearer $DVARA_ACTUATOR_API_KEY" http://localhost:8080/actuator/health/liveness; \
               else \
                 curl -s -o /dev/null http://localhost:8080/actuator/health/liveness; \
               fi'

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
