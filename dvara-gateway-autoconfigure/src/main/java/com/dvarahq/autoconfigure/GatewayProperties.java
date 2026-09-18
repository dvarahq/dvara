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
package com.dvarahq.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties("dvara.llm-gateway")
public class GatewayProperties {

    private Providers providers = new Providers();
    private List<RouteDefinition> routes = new ArrayList<>();
    private Resilience resilience = new Resilience();
    private RateLimitConfig rateLimit = new RateLimitConfig();
    // Encryption (dvara.encryption.*) and region (dvara.region.*) live in their own
    // @ConfigurationProperties classes, GatewayEncryptionProperties and GatewayRegionProperties,
    // so their prefixes stay outside the LLM-gateway-only dvara.llm-gateway namespace.

    @Data
    public static class Providers {
        private ProviderConfig openai       = new ProviderConfig();
        private ProviderConfig anthropic    = new ProviderConfig();
        private ProviderConfig gemini       = new ProviderConfig();
        private ProviderConfig azureOpenai  = new ProviderConfig();
        private ProviderConfig mistral      = new ProviderConfig();
        private ProviderConfig cohere       = new ProviderConfig();
        private ProviderConfig groq         = new ProviderConfig();
        // OpenAI-compatible providers. Each carries its own credential and base URL and is
        // registered when its API key is non-blank at startup. See ProviderAutoConfiguration.
        private ProviderConfig qwen         = new ProviderConfig();
        private ProviderConfig deepseek     = new ProviderConfig();
        private ProviderConfig moonshot     = new ProviderConfig();
        private ProviderConfig chatglm      = new ProviderConfig();
        private ProviderConfig grok         = new ProviderConfig();
        private OllamaConfig   ollama       = new OllamaConfig();
        private BedrockConfig  bedrock      = new BedrockConfig();
        private MockConfig     mock         = new MockConfig();
    }

    @Data
    public static class ProviderConfig {
        private String apiKey;
        private String baseUrl;
    }

    @Data
    public static class OllamaConfig {
        private boolean enabled = false;
        private String  baseUrl = "http://localhost:11434";
    }

    @Data
    public static class BedrockConfig {
        private boolean enabled   = false;
        private String  accessKey;
        private String  secretKey;
        private String  region    = "us-east-1";
    }

    @Data
    public static class MockConfig {
        private boolean enabled = false;
        private String response = "This is a mock response";
        private int latencyMs = 100;
        private int streamTokenDelayMs = 20;
        private double errorRate = 0.0;
        /**
         * Wiremock-style conditional matchers evaluated in declaration order.
         * First matcher whose {@code when} Groovy predicate returns {@code true} wins.
         * If no matcher matches, falls through to {@link #response}.
         */
        private List<MockMatcher> matchers = new ArrayList<>();
        /**
         * Directory containing {@code *.groovy} scenario files. Each file defines
         * a named scenario with {@code name}, {@code when}, and {@code respond}
         * closures. Scenarios are loaded in filename order and take precedence
         * over {@link #matchers}. The directory is watched for changes and
         * scenarios are hot-reloaded without a restart. Leave blank to disable.
         */
        private String scenariosDir;


        /**
         * Fraction of {@code MOCK_MATCHER_FIRED} audit events to emit when
         * the metered telemetry is active. Valid range: {@code 0.0} to
         * {@code 1.0}. {@code 1.0} (default) writes an audit event on every
         * matcher fire; {@code 0.1} writes roughly 10%; {@code 0.0} disables
         * the audit event entirely (Prometheus counters are always
         * incremented regardless of this value).
         *
         * <p>Set below 1.0 in high-throughput load tests where per-request
         * mock audit writes become a bottleneck. For the mock provider's
         * intended use — CI, smoke tests, per-scenario integration tests —
         * unsampled emission is fine.
         */
        private double auditSampleRate = 1.0;
    }

    /**
     * A conditional mock response matcher. The {@code when} field is a Groovy
     * predicate evaluated against a {@code request} binding (the incoming
     * {@code ChatRequest}). The {@code response} field is a static string
     * or a Groovy script prefixed with {@code groovy:}.
     */
    @Data
    public static class MockMatcher {
        /** Human-readable label used in logs and match telemetry. */
        private String name;
        /** Groovy predicate evaluated against the {@code request} binding. Must return a boolean. */
        private String when;
        /** Static text or a {@code groovy:}-prefixed script. */
        private String response;
    }

    @Data
    public static class RouteDefinition {
        private String id;
        private String modelPattern;
        private String strategy = "model-prefix";
        private List<WeightedProvider> providers = new ArrayList<>();
        private String pinnedModelVersion;
        private int costTolerancePct = 0;
        private long latencySlaMs = 0;
        private java.util.Map<String, String> modelTiers;

        /**
         * Canary A/B test configuration for this route. Activated when
         * {@link #strategy} is {@code canary}. Without this block, a
         * {@code canary} strategy has no baseline / candidate split
         * defined and falls back to the first provider in the pool —
         * see {@link RoutingAutoConfiguration#toRouteConfig} for the
         * binding into {@link com.dvarahq.core.routing.RouteConfig}.
         */
        private CanaryConfigDefinition canaryConfig;

        /**
         * Shadow traffic configuration for this route. This build does not dispatch shadow
         * traffic, so a route that sets this block is refused at startup; see
         * {@link RoutingAutoConfiguration#toRouteConfig}.
         */
        private ShadowConfigDefinition shadowConfig;
    }

    @Data
    public static class WeightedProvider {
        private String provider;
        private int weight = 1;
        private String region;
    }

    /**
     * YAML binding for a canary split. Mirrors {@link com.dvarahq.core.routing.CanaryConfig}; a
     * separate class so the properties binder gets a plain mutable bean rather than core's
     * builder-based type.
     */
    @Data
    public static class CanaryConfigDefinition {
        private String baselineProvider;
        private String candidateProvider;
        /** Percentage of in-scope traffic sent to the candidate, 0–100. */
        private int splitPct;
        /**
         * Optional workspace ID. When set, only requests whose
         * API-key-resolved workspace matches this value enter the split;
         * every other workspace lands on the baseline.
         */
        private String workspaceScope;
        private String testName;
    }

    /**
     * YAML binding for shadow traffic. Mirrors
     * {@link com.dvarahq.core.routing.ShadowConfig}.
     */
    @Data
    public static class ShadowConfigDefinition {
        private String shadowProvider;
        /** Percentage of traffic duplicated to the shadow provider, 0–100. */
        private int samplePct;
        private String workspaceScope;
        private String testName;
    }

    @Data
    public static class Resilience {
        private boolean enabled = true;
        private RetryConfig retry = new RetryConfig();
        private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
        private TimeoutConfig timeout = new TimeoutConfig();
        private FallbackConfig fallback = new FallbackConfig();
        private Map<String, ProviderResilienceOverride> providers = new HashMap<>();
    }

    @Data
    public static class RetryConfig {
        private int maxAttempts = 3;
        private long initialBackoffMs = 500;
        private double backoffMultiplier = 2.0;
        private long maxBackoffMs = 10000;
    }

    @Data
    public static class CircuitBreakerConfig {
        private float failureRateThreshold = 50;
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 5;
        private long waitDurationInOpenStateMs = 30000;
        private int permittedCallsInHalfOpen = 3;
    }

    /**
     * {@code chatTimeoutMs} bounds the whole non-streaming call. {@code streamingTimeoutMs} bounds
     * the stream: the SSE response's lifetime in the streaming controllers (the global value, since
     * the emitter is created before a provider is chosen) and the resilience time limiter around
     * opening the stream (the global value or a provider's override). There is no separate idle
     * bound: a stall inside a stream ends when this lifetime does, and a read parked on the provider
     * returns only when the provider does.
     */
    @Data
    public static class TimeoutConfig {
        private long chatTimeoutMs = 30000;
        private long streamingTimeoutMs = 120000;
    }

    @Data
    public static class FallbackConfig {
        private boolean enabled = true;
    }

    /**
     * A per-provider narrowing of the global resilience settings, field by field.
     *
     * <p>Every field is boxed and unset by default: the global classes carry primitives with
     * defaults, so there "not set" and "set to the default" cannot be told apart. Null here means
     * "no opinion, use the global", the same rule the typed workspace settings follow, so naming
     * one field leaves the others at what the operator configured globally.</p>
     */
    @Data
    public static class ProviderResilienceOverride {
        private RetryOverride retry;
        private CircuitBreakerOverride circuitBreaker;
        private TimeoutOverride timeout;
    }

    /** Null fields inherit {@link Resilience#getRetry()}. */
    @Data
    public static class RetryOverride {
        private Integer maxAttempts;
        private Long initialBackoffMs;
        private Double backoffMultiplier;
        private Long maxBackoffMs;
    }

    /** Null fields inherit {@link Resilience#getCircuitBreaker()}. */
    @Data
    public static class CircuitBreakerOverride {
        private Float failureRateThreshold;
        private Integer slidingWindowSize;
        private Integer minimumNumberOfCalls;
        private Long waitDurationInOpenStateMs;
        private Integer permittedCallsInHalfOpen;
    }

    /** Null fields inherit {@link Resilience#getTimeout()}. */
    @Data
    public static class TimeoutOverride {
        private Long chatTimeoutMs;
        private Long streamingTimeoutMs;
    }

    @Data
    public static class RateLimitConfig {
        private boolean enabled = false;

        /**
         * Per-API-key rate limits — both request count and token budget, per 60 seconds, against
         * the API key from the {@code Authorization: Bearer} header. How the 60 seconds are counted
         * is the limiter's: a token bucket in this process. A limiter that counts a sliding window
         * admits at most this many in any trailing 60 seconds and has no separate burst allowance.
         */
        private PerKeyRateLimitConfig perKey = new PerKeyRateLimitConfig();
    }

    /**
     * Per-API-key rate limits. The same API key bucket holds both the
     * request-count window and the token-budget window — there is no
     * separate "global" cap.
     */
    @Data
    public static class PerKeyRateLimitConfig {
        /**
         * Maximum requests per API key per 60 seconds. Default: 100.
         *
         * <p>A value of 100 allows up to 100 requests per API key in any
         * 60-second span — roughly 1.67 requests per second on average.</p>
         */
        private int requestsPerMinute = 100;

        /**
         * Maximum tokens per API key per 60 seconds. Default: 100000.
         *
         * <p>Both the estimated input tokens (charged at admission) and the
         * provider's reported {@code usage.total_tokens} (charged after the
         * response) count against this budget.</p>
         */
        private int tokensPerMinute = 100_000;
    }

}