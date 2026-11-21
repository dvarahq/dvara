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
package com.dvarahq.providers.support;

import com.dvarahq.core.exception.GatewayException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #55: an image URL is fetched only from a public address, within its limits, and verified by TLS. */
class ImageFetcherTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    @TempDir
    static Path dir;
    private static HttpsServer server;
    private static SSLContext trustingTheServer;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        Path store = dir.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        assertThat(keytool.waitFor()).isZero();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(store.toFile())) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        trustingTheServer = SSLContext.getInstance("TLS");
        trustingTheServer.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        server.createContext("/cat.png", x -> respond(x, 200, "image/png", PNG));
        server.createContext("/page", x -> respond(x, 200, "text/html; charset=utf-8", "<html/>".getBytes()));
        server.createContext("/big.png", x -> respond(x, 200, "image/png", new byte[4096]));
        server.createContext("/unsized.png", x -> {
            x.getResponseHeaders().add("Content-Type", "image/png");
            x.sendResponseHeaders(200, 0);   // no length: the body ends at the close
            try (OutputStream out = x.getResponseBody()) {
                out.write(new byte[4096]);
            }
        });
        server.createContext("/slow.png", x -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(x, 200, "image/png", PNG);
        });
        server.createContext("/moved", x -> redirect(x, "/cat.png"));
        server.createContext("/to-metadata", x -> redirect(x, "https://metadata.test/latest/meta-data/"));
        server.createContext("/missing.png", x -> respond(x, 404, "text/plain", "no".getBytes()));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange x, int status, String type, byte[] body)
            throws java.io.IOException {
        x.getResponseHeaders().add("Content-Type", type);
        x.sendResponseHeaders(status, body.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(body);
        }
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange x, String location) throws java.io.IOException {
        x.getResponseHeaders().add("Location", location);
        x.sendResponseHeaders(302, -1);
        x.close();
    }

    /** Resolves names from a table; nothing reaches real DNS. */
    private static ImageFetcher.Resolver table(Map<String, String> hosts) {
        return host -> {
            String ip = hosts.get(host);
            if (ip == null) throw new UnknownHostException(host);
            return new InetAddress[] {InetAddress.getByName(ip)};
        };
    }

    /** A fetcher that may reach the test server on loopback, and nothing else private. */
    private static ImageFetcher local(long maxBytes, Duration timeout) {
        return new ImageFetcher(true, maxBytes, timeout, IMAGES,
                table(Map.of("localhost", "127.0.0.1", "metadata.test", "169.254.169.254", "evil.test", "127.0.0.1")),
                trustingTheServer.getSocketFactory(), true);
    }

    private static String url(String path) {
        return "https://localhost:" + port + path;
    }

    private static GatewayException refusal(Runnable call) {
        try {
            call.run();
        } catch (GatewayException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    // ── fetched ──────────────────────────────────────────────────────────────────────────────

    @Test
    void anImageIsFetchedAndReturnedAsBase64WithItsType() {
        ImageFetcher.FetchedImage image = local(1024, Duration.ofSeconds(5)).fetch(url("/cat.png"));
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(image.base64())).isEqualTo(PNG);
    }

    @Test
    void aRedirectIsFollowedThroughTheSameChecks() {
        assertThat(local(1024, Duration.ofSeconds(5)).fetch(url("/moved")).mediaType()).isEqualTo("image/png");
    }

    // ── refused before anything is fetched ───────────────────────────────────────────────────

    @Test
    void onlyHttpsIsFetched_andNeverWithCredentials() {
        ImageFetcher f = local(1024, Duration.ofSeconds(5));
        assertThat(refusal(() -> f.fetch("http://localhost:" + port + "/cat.png")).getMessage()).contains("https");
        assertThat(refusal(() -> f.fetch("file:///etc/passwd")).getMessage()).contains("https");
        assertThat(refusal(() -> f.fetch("https://user:pw@localhost:" + port + "/cat.png")).getMessage())
                .contains("credentials");
        assertThat(refusal(() -> f.fetch("https://nowhere.invalid/x.png")).getMessage()).contains("does not resolve");
    }

    @Test
    void aHostResolvingToAPrivateOrMetadataAddressIsRefused_evenWithOnePublicAddress() {
        ImageFetcher f = new ImageFetcher(true, 1024, Duration.ofSeconds(5), IMAGES, host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")},
                trustingTheServer.getSocketFactory(), false);
        assertThat(refusal(() -> f.fetch("https://cdn.example/x.png")).getMessage()).contains("private");

        ImageFetcher strict = new ImageFetcher(true, 1024, Duration.ofSeconds(5), IMAGES,
                table(Map.of("localhost", "127.0.0.1")), trustingTheServer.getSocketFactory(), false);
        assertThat(refusal(() -> strict.fetch(url("/cat.png"))).getMessage()).contains("loopback");
    }

    @Test
    void aRedirectToTheMetadataAddressIsRefused() {
        assertThat(refusal(() -> local(1024, Duration.ofSeconds(5)).fetch(url("/to-metadata"))).getMessage())
                .contains("link-local");
    }

    @Test
    void theCertificateMustNameTheHost_soAPinnedAddressCannotBeImpersonated() {
        // evil.test resolves to the server, whose certificate is for localhost: the handshake fails.
        assertThat(refusal(() -> local(1024, Duration.ofSeconds(5)).fetch("https://evil.test:" + port + "/cat.png"))
                .getMessage()).contains("failed");
    }

    // ── refused while fetching ───────────────────────────────────────────────────────────────

    @Test
    void notAnImage_tooLarge_slow_orNotFound_isRefusedNamingTheReason() {
        assertThat(refusal(() -> local(1024, Duration.ofSeconds(5)).fetch(url("/page"))).getMessage())
                .contains("not an accepted image type (text/html)");
        assertThat(refusal(() -> local(1024, Duration.ofSeconds(5)).fetch(url("/big.png"))).getMessage())
                .contains("larger than 1024 bytes");
        assertThat(refusal(() -> local(1024, Duration.ofSeconds(5)).fetch(url("/unsized.png"))).getMessage())
                .contains("larger than 1024 bytes");
        assertThat(refusal(() -> local(1024, Duration.ofMillis(300)).fetch(url("/slow.png"))).getMessage())
                .contains("timed out");
        assertThat(refusal(() -> local(1024, Duration.ofSeconds(5)).fetch(url("/missing.png"))).getMessage())
                .contains("HTTP 404");
    }

    @Test
    void aRefusalIsA400() {
        GatewayException e = refusal(() -> local(1024, Duration.ofSeconds(5)).fetch("http://x/y.png"));
        assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void theDisabledFetcherFetchesNothing() {
        assertThat(ImageFetcher.DISABLED.enabled()).isFalse();
        assertThatThrownBy(() -> ImageFetcher.DISABLED.fetch(url("/cat.png"))).isInstanceOf(IllegalStateException.class);
    }

    // ── the address rules ────────────────────────────────────────────────────────────────────

    @Test
    void privateLocalMetadataAndReservedAddressesAreDisallowed_publicOnesAreNot() throws Exception {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1",
                "::1", "::", "fe80::1", "fd00:ec2::254", "fc00::1", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1",
                "::ffff:127.0.0.1", "::ffff:169.254.169.254"}) {
            assertThat(ImageFetcher.disallowed(InetAddress.getByName(ip))).as(ip).isNotNull();
        }
        for (String ip : new String[] {"93.184.216.34", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111"}) {
            assertThat(ImageFetcher.disallowed(InetAddress.getByName(ip))).as(ip).isNull();
        }
    }
}
