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
package com.dvarahq.server.metrics;

import com.dvarahq.core.region.RegionContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMetricsTest {

    private SimpleMeterRegistry registry;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RegionContext regionContext = () -> Optional.empty();
        metrics = new GatewayMetrics(registry, regionContext);
    }

    @Test
    void recordRequest_incrementsCounterAndTimer() {
        metrics.recordRequest("workspace-1", "gpt-4", "openai", "200", Duration.ofMillis(150));

        Counter counter = registry.find("gateway_requests_total")
                .tag("workspace", "workspace-1")
                .tag("model", "gpt-4")
                .tag("provider", "openai")
                .tag("status", "200")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("gateway_latency_seconds")
                .tag("workspace", "workspace-1")
                .tag("model", "gpt-4")
                .tag("provider", "openai")
                .tag("status", "200")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isCloseTo(150.0, org.assertj.core.data.Offset.offset(10.0));
    }

    @Test
    void recordTokens_incrementsInputAndOutputCounters() {
        metrics.recordTokens("workspace-1", "gpt-4", 100, 50);

        Counter input = registry.find("gateway_tokens_total")
                .tag("workspace", "workspace-1")
                .tag("model", "gpt-4")
                .tag("direction", "input")
                .counter();
        assertThat(input).isNotNull();
        assertThat(input.count()).isEqualTo(100.0);

        Counter output = registry.find("gateway_tokens_total")
                .tag("workspace", "workspace-1")
                .tag("model", "gpt-4")
                .tag("direction", "output")
                .counter();
        assertThat(output).isNotNull();
        assertThat(output.count()).isEqualTo(50.0);
    }

    @Test
    void recordTokens_skipsZeroValues() {
        metrics.recordTokens("workspace-1", "gpt-4", 0, 0);

        assertThat(registry.find("gateway_tokens_total").counters()).isEmpty();
    }

    @Test
    void recordProviderError_incrementsCounter() {
        metrics.recordProviderError("openai", "PROVIDER_ERROR");

        Counter counter = registry.find("gateway_provider_errors_total")
                .tag("provider", "openai")
                .tag("error_code", "PROVIDER_ERROR")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }


    @Test
    void recordFallback_incrementsCounter() {
        metrics.recordFallback("openai", "anthropic");

        Counter counter = registry.find("gateway_fallbacks_total")
                .tag("from_provider", "openai")
                .tag("to_provider", "anthropic")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void nullValues_defaultToUnknown() {
        metrics.recordRequest(null, null, null, null, Duration.ofMillis(10));

        Counter counter = registry.find("gateway_requests_total")
                .tag("workspace", "unknown")
                .tag("model", "unknown")
                .tag("provider", "unknown")
                .tag("status", "unknown")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void multipleRequests_accumulate() {
        metrics.recordRequest("t1", "gpt-4", "openai", "200", Duration.ofMillis(100));
        metrics.recordRequest("t1", "gpt-4", "openai", "200", Duration.ofMillis(200));

        Counter counter = registry.find("gateway_requests_total")
                .tag("workspace", "t1")
                .tag("provider", "openai")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);

        Timer timer = registry.find("gateway_latency_seconds")
                .tag("workspace", "t1")
                .tag("provider", "openai")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
    }
}