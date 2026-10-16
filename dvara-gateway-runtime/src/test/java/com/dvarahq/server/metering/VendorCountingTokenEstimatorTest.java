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
package com.dvarahq.server.metering;

import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.policy.guardrail.TiktokenEstimator;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The vendor-count mode: the provider's own count when it gives one, the local count whenever it does not. */
class VendorCountingTokenEstimatorTest {

    private final TokenEstimator local = new TiktokenEstimator();

    private static ChatRequest request(String workspace, String text) {
        Map<String, Object> metadata = new HashMap<>();
        if (workspace != null) {
            metadata.put("workspace_id", workspace);
        }
        return ChatRequest.builder().model("claude-haiku-4-5").metadata(metadata)
                .messages(List.of(MultimodalMessage.user(text))).build();
    }

    private VendorCountingTokenEstimator with(java.util.function.Function<ChatRequest, OptionalInt> vendor) {
        return new VendorCountingTokenEstimator(local, vendor, Duration.ofMillis(200), Duration.ofSeconds(60), 100);
    }

    @Test
    void theVendorsCountIsUsedAndAskedOnce() {
        AtomicInteger asked = new AtomicInteger();
        var estimator = with(r -> {
            asked.incrementAndGet();
            return OptionalInt.of(42);
        });
        ChatRequest request = request("ws-1", "hello there");

        assertThat(estimator.estimateTokens(request)).isEqualTo(42);
        assertThat(estimator.estimateTokens(request)).isEqualTo(42);
        assertThat(asked.get()).as("the rate limiter, the context check and the metering ask once").isEqualTo(1);
    }

    @Test
    void aVendorThatCannotCountGivesTheLocalCount() {
        ChatRequest request = request("ws-1", "hello there");
        assertThat(with(r -> OptionalInt.empty()).estimateTokens(request)).isEqualTo(local.estimateTokens(request));
        assertThat(with(r -> OptionalInt.of(0)).estimateTokens(request)).isEqualTo(local.estimateTokens(request));
    }

    @Test
    void aVendorThatFailsGivesTheLocalCount() {
        ChatRequest request = request("ws-1", "hello there");
        assertThat(with(r -> {
            throw new IllegalStateException("503");
        }).estimateTokens(request)).isEqualTo(local.estimateTokens(request));
    }

    @Test
    void aSlowVendorIsNotWaitedFor() {
        ChatRequest request = request("ws-1", "hello there");
        long start = System.nanoTime();
        int count = with(r -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OptionalInt.of(42);
        }).estimateTokens(request);

        assertThat(count).isEqualTo(local.estimateTokens(request));
        assertThat((System.nanoTime() - start) / 1_000_000).as("about the 200 ms timeout").isLessThan(2_000);
    }

    @Test
    void aRequestWithNoWorkspaceIsNeverSentToAVendor() {
        AtomicInteger asked = new AtomicInteger();
        ChatRequest request = request(null, "hello there");
        assertThat(with(r -> {
            asked.incrementAndGet();
            return OptionalInt.of(42);
        }).estimateTokens(request)).isEqualTo(local.estimateTokens(request));
        assertThat(asked.get()).isZero();
    }

    @Test
    void twoWorkspacesNeverShareACount() {
        var estimator = with(r -> OptionalInt.of("ws-1".equals(VendorCountingTokenEstimator.workspaceOf(r)) ? 10 : 20));
        assertThat(estimator.estimateTokens(request("ws-1", "same text"))).isEqualTo(10);
        assertThat(estimator.estimateTokens(request("ws-2", "same text"))).isEqualTo(20);
    }

    @Test
    void textAndTheBoundAreLocal() {
        var estimator = with(r -> OptionalInt.of(42));
        assertThat(estimator.estimateTokens("hello there")).isEqualTo(local.estimateTokens("hello there"));
        ChatRequest request = request("ws-1", "hello there");
        assertThat(estimator.upperBound(request)).isEqualTo(local.upperBound(request));
    }

    /** The real Anthropic adapter, sent to an address nothing listens on: the count is the local one, and no error. */
    @Test
    void anUnreachableVendorEndpointDegradesToTheLocalCount() {
        SecretProvider secrets = key -> Optional.of("sk-ant-test");
        AnthropicProvider unreachable = new AnthropicProvider(secrets, "http://127.0.0.1:1", RestClient.builder());
        var estimator = new VendorCountingTokenEstimator(local, unreachable::countInputTokens,
                Duration.ofSeconds(2), Duration.ofSeconds(60), 100);
        ChatRequest request = request("ws-1", "hello there");

        assertThat(estimator.estimateTokens(request)).isEqualTo(local.estimateTokens(request));
    }
}
