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
package com.dvarahq.server.config;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

/**
 * Test configuration that provides an in-memory MeterRegistry and stand-in
 * GatewayMetrics and RateLimiter beans for {@code @WebMvcTest} slices where
 * the full autoconfiguration is absent.
 */
@TestConfiguration
public class TestMetricsConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    RegionContext regionContext() {
        return () -> Optional.empty();
    }

    @Bean
    GatewayMetrics gatewayMetrics(MeterRegistry registry, RegionContext regionContext) {
        return new GatewayMetrics(registry, regionContext);
    }

    @Bean
    RateLimiter rateLimiter() {
        return new RateLimiter() {
            @Override
            public boolean tryAcquire(String key) { return true; }
            @Override
            public boolean tryAcquire(String key, int permits) { return true; }
        };
    }

    @Bean
    AuditWriter auditWriter() {
        return event -> {};
    }

    /**
     * Char-based estimator with the same shape as the gateway's default.
     * Returns rough but stable counts so streaming-token-usage tests can
     * assert behaviour without depending on any particular tokenizer.
     */
    @Bean
    TokenEstimator tokenEstimator() {
        return new TokenEstimator() {
            @Override
            public int estimateTokens(com.dvarahq.core.model.ChatRequest request) {
                if (request == null || request.getMessages() == null) return 0;
                int chars = request.getMessages().stream()
                        .filter(m -> m != null && m.getContent() != null)
                        .flatMap(m -> m.getContent().stream())
                        .filter(p -> p != null && p.textContent() != null)
                        .mapToInt(p -> p.textContent().length())
                        .sum();
                return Math.max(1, chars / 4);
            }

            @Override
            public int estimateTokens(String text) {
                return text == null ? 0 : Math.max(1, text.length() / 4);
            }
        };
    }
}