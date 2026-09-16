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
package com.dvarahq.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.core.model.SseChunk;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * With the emit thread parked in the provider iterator's {@code hasNext()} on an upstream that
 * sent one event and then stopped, releasing the transport from another thread returns that read.
 * This is checked against a real HTTP stream over the transports the server runs on, not a latch
 * that {@code close()} releases directly.
 *
 * <p>Closing the reader would not do it: the parked {@code readLine()} holds the reader's lock.
 */
class StalledStreamReleaseTest {

    private HttpServer server;
    private final CountDownLatch serverMayFinish = new CountDownLatch(1);
    private final CountDownLatch firstEventSent = new CountDownLatch(1);

    @BeforeEach
    void startAStallingUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            boolean gzip = "gzip".equals(exchange.getRequestHeaders().getFirst("X-Test-Encoding"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            if (gzip) {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            exchange.sendResponseHeaders(200, 0);   // chunked: the body has no known length
            OutputStream raw = exchange.getResponseBody();
            OutputStream out = raw;
            if (gzip) {
                // The gzip header goes out (the decoder reads it eagerly) and then nothing: the first
                // read parks inside the decoder's fill(), which is the shape a stalled encoded stream has.
                out = new java.util.zip.GZIPOutputStream(raw, true);
                out.flush();
            } else {
                out.write(("data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            firstEventSent.countDown();
            try { serverMayFinish.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            try { out.close(); } catch (Exception ignored) { }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        serverMayFinish.countDown();
        server.stop(0);
    }

    private Iterator<SseChunk> openStream(org.springframework.http.client.ClientHttpRequestFactory factory, boolean gzip) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .defaultHeader("X-Test-Encoding", gzip ? "gzip" : "identity");
        if (factory != null) {
            builder.requestFactory(factory);
        }
        RestClient client = builder.build();
        OpenAiProvider provider = new OpenAiProvider(client);
        return provider.streamChat(ChatRequest.builder().model("gpt-4o").messages(List.of()).build());
    }

    /** The JDK client: Boot's default when nothing else is on the classpath. */
    @Test
    void jdkClient_releasingTheTransportReturnsAReadParkedOnAStalledUpstream() throws Exception {
        assertRelease(new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    /**
     * The other client a RestClient can run on, taken whenever Apache HttpClient 5 is on the
     * classpath. Its body stream drains on close, so the release goes through {@code abort()}; this
     * is where that is checked.
     */
    @Test
    void apacheClient_releasingTheTransportReturnsAReadParkedOnAStalledUpstream() throws Exception {
        assertRelease(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory());
    }

    /**
     * HC5 decompresses transparently, so the body the reader wraps is a GZIPInputStream and the
     * abortable stream is underneath it; the release has to reach the entity proxy instead.
     */
    @Test
    void apacheClient_gzipEncoded_releasingTheTransportReturnsAReadParkedOnAStalledUpstream() throws Exception {
        assertRelease(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(), true);
    }

    private void assertRelease(org.springframework.http.client.ClientHttpRequestFactory factory) throws Exception {
        assertRelease(factory, false);
    }

    private void assertRelease(org.springframework.http.client.ClientHttpRequestFactory factory, boolean gzip) throws Exception {
        Iterator<SseChunk> chunks = openStream(factory, gzip);
        assertThat(firstEventSent.await(5, TimeUnit.SECONDS)).isTrue();
        if (!gzip) {
            assertThat(chunks.hasNext()).isTrue();
            assertThat(chunks.next().getDelta()).isEqualTo("Hello");
        }
        assertThat(chunks).isInstanceOf(ReleasableUpstream.class);

        // The emit thread's position: parked in hasNext() waiting for a next event that never comes.
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread reader = Thread.startVirtualThread(() -> {
            try {
                outcome.set(chunks.hasNext());
            } catch (RuntimeException e) {
                outcome.set(e);
            } finally {
                returned.countDown();
            }
        });
        boolean returnedEarly = returned.await(1, TimeUnit.SECONDS);
        assertThat(returnedEarly).as("the read really is parked (it returned with: " + outcome.get() + ")").isFalse();

        // The release must not itself wait for the server: draining the body would (Spring's
        // ClientHttpResponse.close does), and the timeout thread that calls this is the container's.
        long t0 = System.nanoTime();
        ((ReleasableUpstream) chunks).releaseTransport();
        long releaseMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(releaseMs).as("releaseTransport returned promptly, not when the server did").isLessThan(2_000);

        assertThat(returned.await(5, TimeUnit.SECONDS))
                .as("releasing the transport from another thread returned the parked read (outcome: " + outcome.get() + ")")
                .isTrue();
        reader.join(1000);
    }
}
