FROM eclipse-temurin:25-jre-jammy AS extract
WORKDIR /app
ARG JAR_FILE=app.jar
COPY ${JAR_FILE} app.jar
# Plain jars (app.jar plus lib/), not the exploded launcher layout: the AOT cache built below can be
# used only when the class path is made of jar files.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r dvara && useradd -r -g dvara -d /app dvara

# JVM defaults. The JVM reads JAVA_TOOL_OPTIONS itself, so they apply although the entrypoint below
# runs java directly, with no shell. Add or override an option with JDK_JAVA_OPTIONS: the java
# launcher puts it after these, and the last value of an option wins. Setting JAVA_TOOL_OPTIONS
# replaces all of them.
#
# The garbage collector is the first option: G1. ZGC stalled under load when the gateway shared its
# cores with other work, and G1 did not. To run ZGC instead, set JDK_JAVA_OPTIONS to
# "-XX:-UseG1GC -XX:+UseZGC"; the AOT cache was built under G1, so the JVM then starts without it
# and logs that it could not use it.
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError"

COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./

RUN mkdir -p /var/lib/dvara && chown -R dvara:dvara /app /var/lib/dvara

USER dvara

# AOT cache (JDK 25): a training run starts the application up to a refreshed context, records the
# classes it loaded and linked and how its methods ran, and writes them to app.aot. Starting from
# the cache took the gateway from 2.0 s to 1.4 s to a refreshed context on a laptop. It is built
# under the defaults above, which the cache must match; the JVM ignores a cache that does not match
# and starts as it would without one. The dummy keys register two providers, so their classes are
# in the cache too; nothing is called.
RUN OPENAI_API_KEY=aot-training ANTHROPIC_API_KEY=aot-training \
    java -XX:AOTCacheOutput=/app/app.aot -Dspring.context.exit=onRefresh -jar /app/app.jar > /tmp/aot-training.log 2>&1 \
    || { cat /tmp/aot-training.log; exit 1; }; \
    rm -f /tmp/aot-training.log; test -s /app/app.aot

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD sh -c 'if [ -n "$DVARA_ACTUATOR_API_KEY" ]; then \
                 curl -sf -H "Authorization: Bearer $DVARA_ACTUATOR_API_KEY" http://localhost:8080/actuator/health/liveness; \
               else \
                 curl -s -o /dev/null http://localhost:8080/actuator/health/liveness; \
               fi'

ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]
