/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.server.observability;

import com.dvarahq.autoconfigure.ObservabilityAutoConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traces are sent with Java's own HTTP client, not OkHttp, and every setting the gateway offers for
 * OTLP export still works with it: headers, gzip, TLS from an SSL bundle and the JVM's proxy
 * settings. gRPC needs OkHttp, so a gateway asked for it refuses to start and says why.
 */
class OtlpSenderTest {

    private static final List<Class<?>> TRACING = List.of(
            ObservationAutoConfiguration.class,
            MicrometerTracingAutoConfiguration.class,
            OpenTelemetrySdkAutoConfiguration.class,
            OpenTelemetryTracingAutoConfiguration.class,
            OtlpTracingAutoConfiguration.class,
            ObservabilityAutoConfiguration.class);

    /** One request the stand-in collector received. */
    record Received(String method, String uri, String host, Map<String, List<String>> headers, byte[] body) {

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    @Test
    void theOnlyHttpSenderIsJavasOwnClient() {
        List<String> providers = ServiceLoader.load(HttpSenderProvider.class).stream()
                .map(p -> p.type().getName()).toList();
        assertThat(providers)
                .as("exactly one HTTP sender, Java's own; with none the exporter cannot be built")
                .containsExactly("io.opentelemetry.exporter.sender.jdk.internal.JdkHttpSenderProvider");
    }

    @Test
    void okHttpAndTheKotlinLibraryAreNotOnTheClasspath() {
        for (String name : List.of("okhttp3.OkHttpClient", "okio.Buffer", "kotlin.Unit")) {
            assertThat(loadable(name)).as("%s is on the classpath", name).isFalse();
        }
    }

    @Test
    void spansExportOverHttpWithTheConfiguredHeadersAndGzip() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/", exchange -> record(exchange, received));
        collector.start();
        try {
            runner("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces",
                    "management.opentelemetry.tracing.export.otlp.headers.x-probe=yes",
                    "management.opentelemetry.tracing.export.otlp.compression=gzip")
                    .run(context -> exportOneSpan(context.getBean(Tracer.class),
                            context.getBean(SdkTracerProvider.class)));
        } finally {
            collector.stop(0);
        }
        assertThat(received).isNotEmpty();
        Received first = received.getFirst();
        assertThat(first.method()).isEqualTo("POST");
        assertThat(first.uri()).isEqualTo("/v1/traces");
        assertThat(first.header("Content-Type")).isEqualTo("application/x-protobuf");
        assertThat(first.header("x-probe")).isEqualTo("yes");
        assertThat(first.header("Content-Encoding")).isEqualTo("gzip");
        assertThat(new String(gunzip(first.body()), StandardCharsets.ISO_8859_1))
                .as("the span name is in the exported batch").contains("otlp-sender-probe");
    }

    @Test
    void spansExportOverTlsWithTheTrustFromAnSslBundle(@TempDir Path dir) throws Exception {
        Path keystore = selfSignedKeystore(dir);
        List<Received> received = new CopyOnWriteArrayList<>();
        HttpsServer collector = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.setHttpsConfigurator(new HttpsConfigurator(serverContext(keystore)));
        collector.createContext("/", exchange -> record(exchange, received));
        collector.start();
        try {
            runner("https://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces",
                    "management.opentelemetry.tracing.export.otlp.ssl.bundle=collector",
                    "spring.ssl.bundle.jks.collector.truststore.location=file:" + keystore,
                    "spring.ssl.bundle.jks.collector.truststore.password=changeit",
                    "spring.ssl.bundle.jks.collector.truststore.type=PKCS12")
                    .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
                    .run(context -> exportOneSpan(context.getBean(Tracer.class),
                            context.getBean(SdkTracerProvider.class)));
        } finally {
            collector.stop(0);
        }
        assertThat(received).as("the collector's certificate is trusted through the bundle").isNotEmpty();
        assertThat(received.getFirst().uri()).isEqualTo("/v1/traces");
    }

    @Test
    void theJvmProxySettingsAreStillUsed() throws Exception {
        // OkHttp used the JVM's default proxy selector, so -Dhttp.proxyHost worked. Java's client
        // uses it too. The collector's name cannot resolve, so only the proxy can deliver the batch.
        List<Received> received = new CopyOnWriteArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> record(exchange, received));
        proxy.start();
        try {
            Probe run = probe(Map.of(), List.of(
                    "-Dhttp.proxyHost=127.0.0.1",
                    "-Dhttp.proxyPort=" + proxy.getAddress().getPort(),
                    "-Dmanagement.opentelemetry.tracing.export.otlp.endpoint=http://collector.invalid:4318/v1/traces"));
            assertThat(run.exit()).as("the probe exits cleanly:%n%s", run.output()).isZero();
        } finally {
            proxy.stop(0);
        }
        assertThat(received).as("the batch went through the proxy").isNotEmpty();
        assertThat(received.getFirst().uri()).isEqualTo("http://collector.invalid:4318/v1/traces");
    }

    @Test
    void grpcIsRefusedAtStartWithAClearMessage() throws Exception {
        // The standard variable Spring Boot maps to its gRPC transport.
        Probe run = probe(Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4317",
                "OTEL_EXPORTER_OTLP_PROTOCOL", "grpc"), List.of());
        assertThat(run.exit()).as("the probe refuses to start:%n%s", run.output()).isNotZero();
        assertThat(run.output()).contains("OTLP export over gRPC is not supported")
                .contains("http/protobuf");
    }

    @Test
    void grpcIsRefusedWhenSetByTheBootProperty() throws Exception {
        Probe run = probe(Map.of(), List.of(
                "-Dmanagement.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:4317",
                "-Dmanagement.opentelemetry.tracing.export.otlp.transport=grpc"));
        assertThat(run.exit()).as("the probe refuses to start:%n%s", run.output()).isNotZero();
        assertThat(run.output()).contains("OTLP export over gRPC is not supported");
    }

    @Test
    void grpcWithNoEndpointIsHarmless() throws Exception {
        // No endpoint means no exporter, so the transport is never used and the start goes ahead.
        Probe run = probe(Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "grpc"), List.of());
        assertThat(run.exit()).as("the probe starts:%n%s", run.output()).isZero();
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static ApplicationContextRunner runner(String endpoint, String... properties) {
        List<String> all = new ArrayList<>(List.of(
                "management.tracing.sampling.probability=1.0",
                "management.opentelemetry.tracing.export.otlp.endpoint=" + endpoint));
        all.addAll(List.of(properties));
        return new ApplicationContextRunner()
                .withPropertyValues(all.toArray(String[]::new))
                .withConfiguration(AutoConfigurations.of(TRACING.toArray(Class<?>[]::new)));
    }

    private static void exportOneSpan(Tracer tracer, SdkTracerProvider provider) {
        tracer.nextSpan().name("otlp-sender-probe").start().end();
        assertThat(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess())
                .as("the batch was accepted").isTrue();
    }

    private static void record(HttpExchange exchange, List<Received> received) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Host"), Map.copyOf(exchange.getRequestHeaders()), body));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return in.readAllBytes();
        }
    }

    private static boolean loadable(String name) {
        try {
            Class.forName(name, false, OtlpSenderTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** A PKCS12 store with a self-signed certificate for 127.0.0.1, made by the JDK's keytool. */
    private static Path selfSignedKeystore(Path dir) throws Exception {
        Path store = dir.resolve("collector.p12");
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "collector", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=collector", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor(1, TimeUnit.MINUTES)).isTrue();
        assertThat(keytool.exitValue()).as("keytool: %s", output).isZero();
        return store;
    }

    private static SSLContext serverContext(Path keystore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystore.toFile())) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    record Probe(int exit, String output) {
    }

    /**
     * Runs {@link OtlpExportProbe} in a new JVM, since Boot maps the OpenTelemetry variables only
     * from the real process environment. JVM options go before the main class.
     */
    private static Probe probe(Map<String, String> environment, List<String> jvmOptions) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dmanagement.tracing.sampling.probability=1.0"));
        command.addAll(jvmOptions);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), OtlpExportProbe.class.getName()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(name -> name.startsWith("OTEL_")
                || name.startsWith("MANAGEMENT_OPENTELEMETRY_") || name.startsWith("MANAGEMENT_TRACING_"));
        builder.environment().putAll(environment);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).isTrue();
        return new Probe(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }
}
