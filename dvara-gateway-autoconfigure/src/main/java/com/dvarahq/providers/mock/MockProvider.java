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
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** Prompt tokens reported per image, and per {@code detail: low} image (#56). */
    private int imageTokens = 765;
    private int imageTokensLow = 85;

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

        MockReply reply = resolveResponse(request);
        String text = applyResponseFormat(reply.text(), request.getResponseFormat());
        int promptTokens = promptTokens(reply, request);
        int completionTokens = completionTokens(reply, text);

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

        MockReply reply = resolveResponse(request);
        String text = applyResponseFormat(reply.text(), request.getResponseFormat());
        String[] words = text.split("\\s+");
        String id = "mock-" + UUID.randomUUID().toString().replace("-", "");

        return new MockSseIterator(words, id, request.getModel(), streamTokenDelayMs,
                promptTokens(reply, request), completionTokens(reply, text));
    }

    /** A scenario's own usage wins (#56); otherwise the estimate. */
    private int promptTokens(MockReply reply, ChatRequest request) {
        return reply.promptTokens() != null ? reply.promptTokens() : estimateTokens(request);
    }

    private static int completionTokens(MockReply reply, String text) {
        return reply.completionTokens() != null ? reply.completionTokens() : text.length() / 4;
    }

    @Override
    public ProviderCapabilities capabilities() {
        // 7-arg form: supportsBatch=true — the Mock provider drives the Batch API e2e path.
        return new ProviderCapabilities(true, false, false, true, true, true, 128_000);
    }

    // ---- Batch API: canned lifecycle for dev / CI -------------------
    // uploadFile -> a file object whose content the Mock keeps, so reading the file back returns what
    // was uploaded; createBatch -> a job that is reported "completed" on the next poll, with an output
    // file whose per-line usage the metering path sums. The output file is the same canned one for
    // every batch. A batch id is derived from its input file id, so a poll needs no stored state.

    private static final String FILE_PREFIX = "mock-file-";
    private static final String BATCH_PREFIX = "mock-batch-";

    /** The id of the canned output file every batch reports. */
    static final String OUTPUT_FILE_ID = FILE_PREFIX + "out";

    /** How many uploaded files the Mock keeps. The oldest is forgotten first. */
    static final int KEPT_FILES = 256;

    /** Uploaded file id to its content, as it was uploaded. */
    private final Map<String, byte[]> files = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > KEPT_FILES;
        }
    });

    @Override
    public String uploadFile(byte[] content, String filename, String purpose) {
        checkErrorRate();
        String id = FILE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        files.put(id, content.clone());
        return "{\"id\":\"" + id + "\",\"object\":\"file\",\"bytes\":" + content.length
                + ",\"filename\":\"" + (filename != null ? filename : "input.jsonl")
                + "\",\"purpose\":\"" + purpose + "\",\"status\":\"processed\"}";
    }

    @Override
    public String createBatch(String requestJson) {
        checkErrorRate();
        String inputFileId;
        try {
            inputFileId = JsonMapper.instance().readTree(requestJson).path("input_file_id").asText("");
        } catch (Exception e) {
            inputFileId = "";
        }
        String batchId = inputFileId.startsWith(FILE_PREFIX)
                ? BATCH_PREFIX + inputFileId.substring(FILE_PREFIX.length())
                : BATCH_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        return "{\"id\":\"" + batchId + "\",\"object\":\"batch\",\"status\":\"validating\","
                + "\"endpoint\":\"/v1/chat/completions\",\"input_file_id\":\"" + inputFileId + "\"}";
    }

    @Override
    public String getBatch(String batchId) {
        return "{\"id\":\"" + batchId + "\",\"object\":\"batch\",\"status\":\"completed\","
                + "\"input_file_id\":\"" + inputFileOf(batchId) + "\",\"output_file_id\":\"" + OUTPUT_FILE_ID + "\","
                + "\"request_counts\":{\"total\":2,\"completed\":2,\"failed\":0}}";
    }

    @Override
    public String cancelBatch(String batchId) {
        // "cancelled" with an output file: the shape that actually needs handling, because a
        // cancelled batch still bills for the requests that finished before it stopped.
        return "{\"id\":\"" + batchId + "\",\"object\":\"batch\",\"status\":\"cancelled\","
                + "\"input_file_id\":\"" + inputFileOf(batchId) + "\",\"output_file_id\":\"" + OUTPUT_FILE_ID + "\","
                + "\"request_counts\":{\"total\":2,\"completed\":2,\"failed\":0}}";
    }

    private static String inputFileOf(String batchId) {
        return batchId.startsWith(BATCH_PREFIX) ? FILE_PREFIX + batchId.substring(BATCH_PREFIX.length()) : "";
    }

    /**
     * An uploaded file's own content, or the canned output file for {@link #OUTPUT_FILE_ID}. Any
     * other id is answered as a provider answers it: not found.
     */
    @Override
    public byte[] getFileContent(String fileId) {
        if (OUTPUT_FILE_ID.equals(fileId)) {
            // Two completed request lines; the metering path sums prompt/completion per model.
            String jsonl =
                    "{\"custom_id\":\"1\",\"response\":{\"status_code\":200,\"body\":{\"model\":\"mock/batch-model\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}}}\n"
                  + "{\"custom_id\":\"2\",\"response\":{\"status_code\":200,\"body\":{\"model\":\"mock/batch-model\",\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8,\"total_tokens\":28}}}}\n";
            return jsonl.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        byte[] content = files.get(fileId);
        if (content == null) {
            throw GatewayException.upstream(404, "Mock file download error 404: no such file " + fileId);
        }
        return content.clone();
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
    private MockReply resolveResponse(ChatRequest request) {
        for (MockMatcher matcher : fileScenarios) {
            if (matcher.matches(request)) {
                fireTelemetryMatched(matcher, MockMatcherTelemetry.Source.FILE, request);
                return matcher.reply(request);
            }
        }
        for (MockMatcher matcher : yamlMatchers) {
            if (matcher.matches(request)) {
                fireTelemetryMatched(matcher, MockMatcherTelemetry.Source.YAML, request);
                return matcher.reply(request);
            }
        }
        fireTelemetryFallthrough(request);
        return MockReply.text(response.evaluate(request));
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

    /** Sets the prompt tokens reported per image, and per {@code detail: low} image (#56). */
    public void setImageTokens(int perImage, int perLowDetailImage) {
        this.imageTokens = Math.max(0, perImage);
        this.imageTokensLow = Math.max(0, perLowDetailImage);
    }

    private int estimateTokens(ChatRequest request) {
        if (request.getMessages() == null) return 0;
        int chars = 0;
        int images = 0;
        for (var m : request.getMessages()) {
            if (m.getContent() == null) continue;
            for (var b : m.getContent()) {
                // The text by its length, not the block's toString; an image by an allowance, not its
                // base64 data (#56: counting nothing billed a scripted vision call as its text alone).
                if (b instanceof com.dvarahq.core.model.ContentBlock.ImageBlock ib) {
                    images += "low".equalsIgnoreCase(ib.detail()) ? imageTokensLow : imageTokens;
                } else if (b.textContent() != null) {
                    chars += b.textContent().length();
                }
            }
        }
        return chars / 4 + images;
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