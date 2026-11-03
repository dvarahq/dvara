# The build stages run on the JDK, which has jdeps and jlink. The image gets only the Java runtime linked below.
FROM eclipse-temurin:25-jdk-jammy AS extract
WORKDIR /app
ARG JAR_FILE=app.jar
COPY ${JAR_FILE} app.jar
# Plain jars (app.jar plus lib/), not the exploded launcher layout: the AOT cache built below can be
# used only when the class path is made of jar files.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- A Java runtime with only the modules the gateway uses ----
# The JRE image's runtime has 48 modules, among them the Graal compiler, and takes 191 MB. jlink links only the
# modules listed below. Two checks keep the list complete:
#
# 1. jdeps reads every jar the image ships and names the modules they use. The build stops on one that is not
#    listed and names it. jdeps reads copies of the jars with their module-info taken out: given a jar with one, it
#    treats the jar as a module and stops at the first module that jar names and the image lacks.
# 2. jdeps cannot see the modules the JDK loads by name, as a service or for a JVM option. The probe below uses
#    each of them on the image's own runtime, so a missing one fails the build in the final stage.
#
# --strip-debug drops the JDK classes' debug attributes; the JRE image had none either. Temurin 25 ships no jmods,
# so jlink links from the JDK's own runtime image. No default CDS archive: the gateway starts from its AOT cache.
FROM extract AS jre
COPY <<'EOF' jre-modules.txt
# One module per line; text after # is a comment.

# Found by jdeps on the application's jars.
java.base
java.compiler
java.desktop            # java.beans
java.instrument
java.naming
java.net.http           # OTLP trace export, and the HTTP client
java.prefs
java.scripting
java.security.jgss
java.sql
jdk.jfr
jdk.management          # process and OS metrics (Micrometer)
jdk.unsupported         # sun.misc.Unsafe

# Loaded at run time only.
jdk.charsets            # character sets beyond the common ones
jdk.crypto.cryptoki     # the SunPKCS11 provider java.security lists, for keys in an HSM
jdk.jdwp.agent          # -agentlib:jdwp, to attach a debugger
jdk.management.agent    # -Dcom.sun.management.jmxremote, remote JMX
jdk.management.jfr      # JFR over JMX
jdk.naming.dns          # DNS lookups through JNDI
jdk.security.auth       # Kerberos and other JAAS login modules
jdk.security.jgss       # SASL GSSAPI
jdk.zipfs               # jar: and zip: file systems
EOF
RUN set -eu; \
    apt-get update && apt-get install -y --no-install-recommends zip >/dev/null && rm -rf /var/lib/apt/lists/*; \
    sed 's/#.*//' jre-modules.txt | tr -s ' \t' '\n' | grep . | LC_ALL=C sort -u > /tmp/listed; \
    mkdir /tmp/jdeps; n=0; \
    for j in $(find extracted -name '*.jar'); do n=$((n+1)); cp "$j" "/tmp/jdeps/$n-$(basename "$j")"; done; \
    for j in /tmp/jdeps/*.jar; do \
      zip -q -d "$j" 'module-info.class' 'META-INF/versions/*/module-info.class' >/dev/null 2>&1 || true; done; \
    jdeps --ignore-missing-deps -q --multi-release 25 --print-module-deps /tmp/jdeps/*.jar > /tmp/jdeps.out; \
    tr ',' '\n' < /tmp/jdeps.out | grep . | LC_ALL=C sort -u > /tmp/found; \
    grep -qx java.base /tmp/found || { echo "FATAL: jdeps named no modules:"; cat /tmp/jdeps.out; exit 1; }; \
    missing=$(LC_ALL=C comm -23 /tmp/found /tmp/listed | tr '\n' ' '); \
    [ -z "$missing" ] || { echo "FATAL: the application uses Java modules the list does not have: $missing"; exit 1; }; \
    echo "jdeps found: $(paste -sd' ' /tmp/found)"; \
    jlink --add-modules "$(paste -sd, /tmp/listed)" --strip-debug --no-man-pages --no-header-files --output /jre; \
    rm -rf /tmp/jdeps

# Check 2. One line for each module in the second group of the list. jdk.jdwp.agent and jdk.management.agent have
# no line: without them the JVM options the probe runs with stop the JVM at start.
COPY <<'EOF' /probe/RuntimeProbe.java
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.file.spi.FileSystemProvider;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class RuntimeProbe {
    public static void main(String[] args) throws Exception {
        // TLS with the JDK's own providers, and the CA certificates the JVM trusts.
        SSLContext tls = SSLContext.getInstance("TLSv1.3");
        tls.init(null, null, null);
        check(List.of(tls.createSSLEngine().getEnabledProtocols()).contains("TLSv1.3"), "TLS 1.3");
        for (String algorithm : List.of("RSA", "EC", "X25519", "Ed25519")) {
            KeyPairGenerator.getInstance(algorithm);
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((KeyStore) null);
        int cas = ((X509TrustManager) trust.getTrustManagers()[0]).getAcceptedIssuers().length;
        check(cas > 100, "its CA certificates (it trusts " + cas + ")");

        check(Charset.isSupported("IBM037"), "jdk.charsets");
        check(Security.getProvider("SunPKCS11") != null, "jdk.crypto.cryptoki");
        ManagementFactory.getPlatformMXBean(jdk.management.jfr.FlightRecorderMXBean.class).getRecordings();
        Class.forName("com.sun.jndi.dns.DnsContextFactory");
        Class.forName("com.sun.security.auth.module.Krb5LoginModule");
        check(Security.getProvider("JdkSASL") != null, "jdk.security.jgss");
        check(FileSystemProvider.installedProviders().stream().anyMatch(p -> p.getScheme().equals("jar")), "jdk.zipfs");
        System.out.println("RuntimeProbe: the runtime has every module the image loads at run time");
        System.exit(0);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FATAL: the image's Java runtime lacks " + what + "; add it to the module list");
            System.exit(1);
        }
    }
}
EOF
RUN javac -d /probe /probe/RuntimeProbe.java

FROM ubuntu:jammy
WORKDIR /app

# What the JRE image installed (fonts, CA certificates, time zones, the en_US.UTF-8 locale), plus curl for the
# health check. The JVM trusts the CA certificates in its own lib/security/cacerts, the same file the JRE image has.
RUN apt-get update && apt-get upgrade -y \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
         curl ca-certificates fontconfig locales tzdata \
    && echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen && locale-gen en_US.UTF-8 \
    && rm -rf /var/lib/apt/lists/*

# The Java runtime linked above, where and as the JRE image had it.
ENV JAVA_HOME=/opt/java/openjdk LANG=en_US.UTF-8 LANGUAGE=en_US:en LC_ALL=en_US.UTF-8
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre /jre ${JAVA_HOME}

# Check 2 from the jre stage, on this image's runtime and OS. Mounted rather than copied, so none of it stays in
# the image. The two agent options load jdk.jdwp.agent and jdk.management.agent; ports 0 bind nothing fixed.
RUN --mount=type=bind,from=jre,source=/probe,target=/probe \
    java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0 \
         -Dcom.sun.management.jmxremote.port=0 -Dcom.sun.management.jmxremote.rmi.port=0 \
         -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false \
         -cp /probe RuntimeProbe

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

# The files are owned by dvara as they are copied. A separate `chown -R` afterwards would store
# every jar a second time in a new layer, about 54 MB.
COPY --from=extract --chown=dvara:dvara /app/extracted/dependencies/ ./
COPY --from=extract --chown=dvara:dvara /app/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=dvara:dvara /app/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=dvara:dvara /app/extracted/application/ ./

# Not recursive: /app itself must be writable for the AOT cache below.
RUN mkdir -p /var/lib/dvara && chown dvara:dvara /app /var/lib/dvara

USER dvara

# AOT cache (JDK 25): a training run starts the application up to a refreshed context, records the
# classes it loaded and linked and how its methods ran, and writes them to app.aot. Starting from
# the cache took the gateway from 2.0 s to 1.4 s to a refreshed context on a laptop. It is built
# under the defaults above, which the cache must match; the JVM ignores a cache that does not match
# and starts as it would without one. The dummy keys register two providers, so their classes are
# in the cache too; nothing is called.
# A buildx builder gives each build step OpenTelemetry settings that point at its own socket, which
# the gateway refuses as a trace endpoint and does not start. The run is cleared of them: nothing
# sets them when the image runs, and the training run must start as the image does.
RUN unset $(env | sed -n 's/^\(OTEL_[A-Za-z0-9_]*\)=.*/\1/p'); \
    OPENAI_API_KEY=aot-training ANTHROPIC_API_KEY=aot-training \
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
