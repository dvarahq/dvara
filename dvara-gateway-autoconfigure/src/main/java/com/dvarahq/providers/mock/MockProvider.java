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
package com.dvarahq.providers.mock;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.AbstractLlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.util.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;

/**
 * Mock LLM provider for testing and CI environments. Returns configurable
 * fake completions with simulated latency and optional error injection.
 * <p>
 * Model prefix: {@code mock/} — e.g. {@code "model": "mock/test-model"}.
 */
public class MockProvider extends AbstractLlmProvider {

    private static final Logger log = LoggerFactory.getLogger(MockProvider.class);

    /** The default response, compiled once when it is a {@code groovy:} script. */
    private final ResponseEvaluator response;

    /**
     * YAML-configured matchers. Immutable and set at construction time.
     * Kept separate from {@link #fileScenarios} so hot reload of file
     * scenarios never disturbs the YAML list.
     */
    private final List<MockMatcher> yamlMatchers;

    /**
     * File-backed scenario matchers. Replaced atomically by
     * {@link #replaceFileScenarios(List)} when the scenarios directory changes.
     * Volatile so the watcher's write and the data-plane thread's read
     * always see a consistent snapshot.
     */
    private volatile List<MockMatcher> fileScenarios = List.of();

    /** Telemetry hook for matcher fire metrics and audit events. */
    private volatile MockMatcherTelemetry telemetry = MockMatcherTelemetry.NOOP;

    private final int latencyMs;
    private final int streamTokenDelayMs;
    private final double errorRate;
    private final Random random;

    public MockProvider(String response, int latencyMs, int streamTokenDelayMs, double errorRate) {
        this(response, List.of(), latencyMs, streamTokenDelayMs, errorRate, new Random());
    }

    public MockProvider(String response,
                        List<? extends MockMatcher> yamlMatchers,
                        int latencyMs, int streamTokenDelayMs, double errorRate) {
        this(response, yamlMatchers, latencyMs, streamTokenDelayMs, errorRate, new Random());
    }

    /** Package-private constructor for unit tests with injectable {@link Random}. */
    MockProvider(String response, int latencyMs, int streamTokenDelayMs, double errorRate, Random random) {
        this(response, List.of(), latencyMs, streamTokenDelayMs, errorRate, random);
    }

    MockProvider(String response,
                 List<? extends MockMatcher> yamlMatchers,
                 int latencyMs, int streamTokenDelayMs, double errorRate, Random random) {
        super("mock");
        this.response = ResponseEvaluator.compile(response);
        this.yamlMatchers = yamlMatchers != null ? List.copyOf(yamlMatchers) : List.of();
        this.latencyMs = latencyMs;
        this.streamTokenDelayMs = streamTokenDelayMs;
        this.errorRate = errorRate;
        this.random = random;
    }

    /**
     * Replaces the set of file-backed scenario matchers. Called by
     * {@link MockScenarioWatcher} on a hot reload, and once at startup by
     * {@code ProviderAutoConfiguration.mockScenarioWatcher(..)} after the
     * initial scenario scan. File scenarios take precedence over YAML matchers.
     */
    public void replaceFileScenarios(List<? extends MockMatcher> scenarios) {
        this.fileScenarios = scenarios != null ? List.copyOf(scenarios) : List.of();
    }

    /**
     * Sets the telemetry hook.
     *
     * <p><b>Intended to be called once, at bean initialization time.</b>
     * {@code ProviderAutoConfiguration} invokes this after bean construction
     * so the NOOP default is replaced with the real metered implementation
     * when one is available. The method is thread-safe via the volatile
     * field, but it is not designed for repeated runtime swapping.
     *
     * @param telemetry the telemetry hook, or {@code null} to revert to NOOP
     */
    public void setTelemetry(MockMatcherTelemetry telemetry) {
        this.telemetry = telemetry != null ? telemetry : MockMatcherTelemetry.NOOP;
    }

    /**
     * Invokes the telemetry hook with failure isolation: any exception
     * thrown by a misbehaving telemetry implementation is logged at WARN
     * and swallowed, so a broken metrics backend cannot take down the
     * data-plane request flow.
     */
    private void fireTelemetryMatched(MockMatcher matcher, MockMatcherTelemetry.Source source, ChatRequest request) {
        try {
            telemetry.matcherFired(matcher.name(), source, request);
        } catch (RuntimeException e) {
            log.warn("Mock matcher telemetry failed for scenario '{}' — continuing without telemetry: {}",
                    matcher.name(), e.getMessage());
        }
    }

    /**
     * Same failure-isolation pattern as {@link #fireTelemetryMatched} but
     * for the no-match fall-through path.
     */
    private void fireTelemetryFallthrough(ChatRequest request) {
        try {
            telemetry.matcherFellThrough(request);
        } catch (RuntimeException e) {
            log.warn("Mock fallthrough telemetry failed — continuing without telemetry: {}", e.getMessage());
        }
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        checkErrorRate();
        simulateLatency(latencyMs);

        String text = resolveResponse(request);
        text = applyResponseFormat(text, request.getResponseFormat());
        int promptTokens = estimateTokens(request);
        int completionTokens = text.length() / 4;

        return ChatResponse.builder()
                .id("mock-" + UUID.randomUUID().toString().replace("-", ""))
                .object("chat.completion")
                .created(Instant.now().getEpochSecond())
                .model(request.getModel())
                .choices(List.of(ChatResponse.Choice.builder()
                        .index(0)
                        .message(MultimodalMessage.assistant(text))
                        .finishReason("stop")
                        .build()))
                .usage(ChatResponse.Usage.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build())
                .build();
    }

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        checkErrorRate();
        simulateLatency(latencyMs);

        String text = applyResponseFormat(resolveResponse(request), request.getResponseFormat());
        String[] words = text.split("\\s+");
        String id = "mock-" + UUID.randomUUID().toString().replace("-", "");

        return new MockSseIterator(words, id, request.getModel(), streamTokenDelayMs, estimateTokens(request), text.length() / 4);
    }

    @Override
    public ProviderCapabilities capabilities() {
        // 7-arg form: supportsBatch=true — the Mock provider drives the Batch API e2e path.
        return new ProviderCapabilities(true, false, false, true, true, true, 128_000);
    }

    // ---- Batch API: canned lifecycle for dev / CI -------------------
    // uploadFile -> file object; createBatch -> a job that is immediately reported
    // "completed" on the next poll, with an output file whose per-line usage the
    // metering path sums. Deterministic + stateless so e2e tests are repeatable.

    @Override
    public String uploadFile(byte[] content, String filename, String purpose) {
        checkErrorRate();
        return "{\"id\":\"mock-file-in\",\"object\":\"file\",\"bytes\":" + content.length
                + ",\"filename\":\"" + (filename != null ? filename : "input.jsonl")
                + "\",\"purpose\":\"" + purpose + "\",\"status\":\"processed\"}";
    }

    @Override
    public String createBatch(String requestJson) {
        checkErrorRate();
        return "{\"id\":\"mock-batch-1\",\"object\":\"batch\",\"status\":\"validating\","
                + "\"endpoint\":\"/v1/chat/completions\",\"input_file_id\":\"mock-file-in\"}";
    }

    @Override
    public String getBatch(String batchId) {
        return "{\"id\":\"" + batchId + "\",\"object\":\"batch\",\"status\":\"completed\","
                + "\"input_file_id\":\"mock-file-in\",\"output_file_id\":\"mock-file-out\","
                + "\"request_counts\":{\"total\":2,\"completed\":2,\"failed\":0}}";
    }

    @Override
    public String cancelBatch(String batchId) {
        // "cancelled" with an output file: the shape that actually needs handling, because a
        // cancelled batch still bills for the requests that finished before it stopped.
        return "{\"id\":\"" + batchId + "\",\"object\":\"batch\",\"status\":\"cancelled\","
                + "\"input_file_id\":\"mock-file-in\",\"output_file_id\":\"mock-file-out\","
                + "\"request_counts\":{\"total\":2,\"completed\":2,\"failed\":0}}";
    }

    @Override
    public byte[] getFileContent(String fileId) {
        // Two completed request lines; the metering path sums prompt/completion per model.
        String jsonl =
                "{\"custom_id\":\"1\",\"response\":{\"status_code\":200,\"body\":{\"model\":\"mock/batch-model\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}}}\n"
              + "{\"custom_id\":\"2\",\"response\":{\"status_code\":200,\"body\":{\"model\":\"mock/batch-model\",\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8,\"total_tokens\":28}}}}\n";
        return jsonl.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("mock/");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        return List.of(new com.dvarahq.core.provider.ModelInfo("mock/test-model", "mock", 0L));
    }

    /**
     * Produces the final response text for a request by walking three layers
     * in order until one of them yields a response:
     *
     * <ol>
     *   <li><b>File scenarios</b> — loaded from the configured {@code scenarios-dir}
     *       and replaceable at runtime by the scenario watcher. Evaluated in
     *       filename order.</li>
     *   <li><b>YAML matchers</b> — loaded from {@code dvara.llm-gateway.providers.mock.matchers}
     *       at startup. Evaluated in declaration order.</li>
     *   <li><b>Default response</b> — the fallback text (or {@code groovy:} script)
     *       configured via {@code dvara.llm-gateway.providers.mock.response}.</li>
     * </ol>
     *
     * Matchers produce their own response directly via {@link MockMatcher#respond(ChatRequest)}.
     * The default response flows through {@link ResponseEvaluator}, so it may be a
     * {@code groovy:}-prefixed script too.
     */
    private String resolveResponse(ChatRequest request) {
        for (MockMatcher matcher : fileScenarios) {
            if (matcher.matches(request)) {
                fireTelemetryMatched(matcher, MockMatcherTelemetry.Source.FILE, request);
                return matcher.respond(request);
            }
        }
        for (MockMatcher matcher : yamlMatchers) {
            if (matcher.matches(request)) {
                fireTelemetryMatched(matcher, MockMatcherTelemetry.Source.YAML, request);
                return matcher.respond(request);
            }
        }
        fireTelemetryFallthrough(request);
        return response.evaluate(request);
    }

    private String applyResponseFormat(String text, ResponseFormat format) {
        if (format instanceof ResponseFormat.JsonObject || format instanceof ResponseFormat.JsonSchema) {
            // Written by the JSON mapper, so a backslash, a newline or a control character in the text is escaped.
            return JsonMapper.instance().createObjectNode().put("result", text).toString();
        }
        return text;
    }

    private void checkErrorRate() {
        if (errorRate > 0 && random.nextDouble() < errorRate) {
            throw new GatewayException("PROVIDER_ERROR", "Mock simulated error");
        }
    }

    private static void simulateLatency(int ms) {
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int estimateTokens(ChatRequest request) {
        if (request.getMessages() == null) return 0;
        int chars = request.getMessages().stream()
                .mapToInt(m -> {
                    if (m.getContent() == null) return 0;
                    return m.getContent().stream()
                            // The text only: not the block's toString, and not an image's base64 data.
                            .mapToInt(b -> b.textContent() != null ? b.textContent().length() : 0)
                            .sum();
                })
                .sum();
        return chars / 4;
    }

    // -------------------------------------------------------------------------
    // SSE stream iterator — yields one word per chunk
    // -------------------------------------------------------------------------

    private static class MockSseIterator implements Iterator<SseChunk> {

        private final String[] words;
        private final String id;
        private final String model;
        private final int delayMs;
        private final int promptTokens;
        private final int completionTokens;
        private int index;
        private boolean sentFinal;

        MockSseIterator(String[] words, String id, String model, int delayMs, int promptTokens, int completionTokens) {
            this.words = words;
            this.id = id;
            this.model = model;
            this.delayMs = delayMs;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public boolean hasNext() {
            return index < words.length || !sentFinal;
        }

        @Override
        public SseChunk next() {
            if (!hasNext()) throw new NoSuchElementException();

            if (index < words.length) {
                if (index > 0) {
                    simulateLatency(delayMs);
                }
                String word = (index > 0 ? " " : "") + words[index];
                index++;
                return SseChunk.builder()
                        .id(id)
                        .model(model)
                        .delta(word)
                        .done(false)
                        .build();
            }

            // Final chunk: carries a usage block the way a real provider's terminal chunk does, so
            // a stream through the mock is billed exactly rather than estimated. The figures are
            // the same accounting the mock's non-streaming path uses.
            sentFinal = true;
            int completion = completionTokens;
            return SseChunk.builder()
                    .id(id)
                    .model(model)
                    .finishReason("stop")
                    .usage(ChatResponse.Usage.builder()
                            .promptTokens(promptTokens)
                            .completionTokens(completion)
                            .totalTokens(promptTokens + completion)
                            .build())
                    .done(true)
                    .build();
        }
    }
}