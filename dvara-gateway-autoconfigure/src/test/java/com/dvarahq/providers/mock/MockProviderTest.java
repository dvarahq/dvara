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
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.util.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockProviderTest {

    private ChatRequest sampleRequest() {
        return ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.user("Hello")))
                .build();
    }

    @Test
    void supports_matchesMockPrefix() {
        var provider = new MockProvider("response", 0, 0, 0.0);
        assertThat(provider.supports(sampleRequest())).isTrue();
    }

    @Test
    void supports_rejectsNonMockPrefix() {
        var provider = new MockProvider("response", 0, 0, 0.0);
        var request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void chat_returnsConfiguredResponse() {
        var provider = new MockProvider("Hello from mock", 0, 0, 0.0);
        ChatResponse resp = provider.chat(sampleRequest());

        assertThat(resp.getId()).startsWith("mock-");
        assertThat(resp.getModel()).isEqualTo("mock/test-model");
        assertThat(resp.getObject()).isEqualTo("chat.completion");
        assertThat(resp.getChoices()).hasSize(1);
        assertThat(resp.getChoices().get(0).getFinishReason()).isEqualTo("stop");

        String content = resp.getChoices().get(0).getMessage().getContent().get(0).toString();
        assertThat(content).contains("Hello from mock");
    }

    @Test
    void chat_estimatesTokensByCharCount() {
        var provider = new MockProvider("abcd", 0, 0, 0.0); // 4 chars → 1 token
        ChatResponse resp = provider.chat(sampleRequest());

        assertThat(resp.getUsage().getCompletionTokens()).isEqualTo(1); // "abcd".length() / 4
        assertThat(resp.getUsage().getTotalTokens())
                .isEqualTo(resp.getUsage().getPromptTokens() + resp.getUsage().getCompletionTokens());
    }

    @Test
    void chat_simulatesLatency() {
        var provider = new MockProvider("ok", 200, 0, 0.0);
        long start = System.currentTimeMillis();
        provider.chat(sampleRequest());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(150); // allow some slack
    }

    @Test
    void chat_errorRateTriggersException() {
        Random fixedRandom = mock(Random.class);
        when(fixedRandom.nextDouble()).thenReturn(0.05); // below 0.1 error rate

        var provider = new MockProvider("ok", 0, 0, 0.1, fixedRandom);

        assertThatThrownBy(() -> provider.chat(sampleRequest()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Mock simulated error");
    }

    @Test
    void chat_noErrorWhenRandomAboveRate() {
        Random fixedRandom = mock(Random.class);
        when(fixedRandom.nextDouble()).thenReturn(0.5); // above 0.1 error rate

        var provider = new MockProvider("ok", 0, 0, 0.1, fixedRandom);
        ChatResponse resp = provider.chat(sampleRequest());
        assertThat(resp).isNotNull();
    }

    @Test
    void streamChat_yieldsWordsAndFinalChunk() {
        var provider = new MockProvider("hello world foo", 0, 0, 0.0);
        Iterator<SseChunk> it = provider.streamChat(sampleRequest());

        List<SseChunk> chunks = new ArrayList<>();
        while (it.hasNext()) {
            chunks.add(it.next());
        }

        // 3 word chunks + 1 final chunk
        assertThat(chunks).hasSize(4);

        // First word has no leading space
        assertThat(chunks.get(0).getDelta()).isEqualTo("hello");
        assertThat(chunks.get(0).isDone()).isFalse();

        // Subsequent words have leading space
        assertThat(chunks.get(1).getDelta()).isEqualTo(" world");
        assertThat(chunks.get(2).getDelta()).isEqualTo(" foo");

        // Final chunk
        assertThat(chunks.get(3).isDone()).isTrue();
        assertThat(chunks.get(3).getFinishReason()).isEqualTo("stop");
    }

    @Test
    void streamChat_errorRateTriggersException() {
        Random fixedRandom = mock(Random.class);
        when(fixedRandom.nextDouble()).thenReturn(0.0); // always triggers error

        var provider = new MockProvider("ok", 0, 0, 1.0, fixedRandom);

        assertThatThrownBy(() -> provider.streamChat(sampleRequest()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Mock simulated error");
    }

    @Test
    void capabilities_supportsStreaming() {
        var provider = new MockProvider("ok", 0, 0, 0.0);
        assertThat(provider.capabilities().supportsStreaming()).isTrue();
        assertThat(provider.capabilities().supportsVision()).isFalse();
        assertThat(provider.capabilities().supportsToolCalls()).isFalse();
        assertThat(provider.capabilities().supportsStructuredOutputs()).isTrue();
        assertThat(provider.capabilities().supportsJsonMode()).isTrue();
        assertThat(provider.capabilities().maxContextTokens()).isEqualTo(128_000);
    }

    @Test
    void name_isMock() {
        var provider = new MockProvider("ok", 0, 0, 0.0);
        assertThat(provider.name()).isEqualTo("mock");
    }

    // -------------------------------------------------------------------------
    // response_format
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonObjectFormat_wrapsResponseInJson() {
        var provider = new MockProvider("Hello", 0, 0, 0.0);
        ChatRequest request = ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        ChatResponse resp = provider.chat(request);
        String content = resp.getChoices().get(0).getMessage().getContent().get(0).toString();
        assertThat(content).contains("{\"result\":\"Hello\"}");
    }

    @Test
    void chat_jsonSchemaFormat_wrapsResponseInJson() {
        var provider = new MockProvider("World", 0, 0, 0.0);
        ChatRequest request = ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .responseFormat(new ResponseFormat.JsonSchema("test", Map.of("type", "object"), false))
                .build();

        ChatResponse resp = provider.chat(request);
        String content = resp.getChoices().get(0).getMessage().getContent().get(0).toString();
        assertThat(content).contains("{\"result\":\"World\"}");
    }

    @Test
    void chat_jsonObjectFormat_escapesBackslashesNewlinesAndControlCharacters() throws Exception {
        String text = "He said \"hi\" from C:\\dir\nnext line\ttab \u0001 end";
        var provider = new MockProvider(text, 0, 0, 0.0);
        ChatRequest request = ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        String content = provider.chat(request).getChoices().get(0).getMessage().getContent().get(0).textContent();

        JsonNode json = JsonMapper.instance().readTree(content);
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get("result").asText()).isEqualTo(text);
    }

    @Test
    void streamChat_jsonObjectFormat_streamsTheWrappedJson() throws Exception {
        var provider = new MockProvider("hello world", 0, 0, 0.0);
        ChatRequest request = ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        Iterator<SseChunk> it = provider.streamChat(request);
        StringBuilder joined = new StringBuilder();
        SseChunk last = null;
        while (it.hasNext()) {
            last = it.next();
            if (last.getDelta() != null) {
                joined.append(last.getDelta());
            }
        }

        assertThat(JsonMapper.instance().readTree(joined.toString()).get("result").asText()).isEqualTo("hello world");
        // The same accounting as the non-streaming path: the wrapped text, 24 characters.
        assertThat(last.getUsage().getCompletionTokens()).isEqualTo(6);
    }

    @Test
    void chat_promptTokensCountMessageTextOnly() {
        var provider = new MockProvider("ok", 0, 0, 0.0);

        ChatRequest text = ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.user("abcdefgh")))
                .build();
        assertThat(provider.chat(text).getUsage().getPromptTokens()).isEqualTo(2);

        ChatRequest withImage = ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("abcd"),
                                new ContentBlock.ImageBlock("image/png", "A".repeat(40_000))))
                        .build()))
                .build();
        assertThat(provider.chat(withImage).getUsage().getPromptTokens()).isEqualTo(1);
    }

    @Test
    void matchers_groovyResponse_isCompiledOnceNotOnEveryRequest() {
        // A script compiled again on each call runs as a new class each time.
        CompiledMatcher m = matcher("once", "true", "groovy: System.identityHashCode(getClass())");
        ChatRequest request = request("mock/any", "hi");

        assertThat(m.respond(request)).isEqualTo(m.respond(request));
    }

    @Test
    void defaultGroovyResponse_isCompiledOnceNotOnEveryRequest() {
        var provider = new MockProvider("groovy: System.identityHashCode(getClass())", 0, 0, 0.0);

        String first = provider.chat(sampleRequest()).getChoices().get(0).getMessage().getContent().get(0).textContent();
        String second = provider.chat(sampleRequest()).getChoices().get(0).getMessage().getContent().get(0).textContent();

        assertThat(first).isEqualTo(second);
    }

    // -------------------------------------------------------------------------
    // listModels
    // -------------------------------------------------------------------------

    @Test
    void listModels_returnsMockTestModel() {
        MockProvider mock = new MockProvider("Hello", 0, 0, 0.0);
        var models = mock.listModels();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo("mock/test-model");
        assertThat(models.get(0).ownedBy()).isEqualTo("mock");
    }

    // -------------------------------------------------------------------------
    // Conditional matchers
    // -------------------------------------------------------------------------

    private CompiledMatcher matcher(String name, String predicate, String response) {
        return new CompiledMatcher(name, new MatcherEvaluator(predicate), response);
    }

    private ChatRequest request(String model, String userText) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user(userText)))
                .temperature(0.7)
                .stream(false)
                .build();
    }

    @Test
    void matchers_firstMatchingPredicateWins() {
        List<CompiledMatcher> matchers = List.of(
                matcher("billing", "request.model == 'mock/billing'", "Your balance is $42"),
                matcher("weather", "request.model == 'mock/weather'", "72°F and sunny"));
        var provider = new MockProvider("default response", matchers, 0, 0, 0.0);

        assertThat(provider.chat(request("mock/billing", "hi")).getChoices().get(0).getMessage()
                .getContent().get(0).toString()).contains("Your balance is $42");
        assertThat(provider.chat(request("mock/weather", "hi")).getChoices().get(0).getMessage()
                .getContent().get(0).toString()).contains("72°F and sunny");
    }

    @Test
    void matchers_orderMatters_earlierMatcherShadowsLater() {
        // Both predicates would match for mock/foo — the first-declared one must win.
        List<CompiledMatcher> matchers = List.of(
                matcher("starts-with-mock", "request.model.startsWith('mock/')", "from starts-with-mock"),
                matcher("equals-foo", "request.model == 'mock/foo'", "from equals-foo"));
        var provider = new MockProvider("default", matchers, 0, 0, 0.0);

        ChatResponse resp = provider.chat(request("mock/foo", "hi"));
        assertThat(resp.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("from starts-with-mock");
    }

    @Test
    void matchers_fallThroughToDefaultWhenNoMatcherFires() {
        List<CompiledMatcher> matchers = List.of(
                matcher("never-fires", "false", "never"));
        var provider = new MockProvider("default response", matchers, 0, 0, 0.0);

        ChatResponse resp = provider.chat(request("mock/other", "hi"));
        assertThat(resp.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("default response");
    }

    @Test
    void matchers_groovyScriptedResponse_evaluatedAgainstRequest() {
        List<CompiledMatcher> matchers = List.of(
                matcher("echo-model", "request.model.startsWith('mock/')",
                        "groovy: 'Request targeted model: ' + request.model"));
        var provider = new MockProvider("default", matchers, 0, 0, 0.0);

        ChatResponse resp = provider.chat(request("mock/echo", "hi"));
        assertThat(resp.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("Request targeted model: mock/echo");
    }

    @Test
    void matchers_predicateException_surfacesAsProviderError() {
        List<CompiledMatcher> matchers = List.of(
                matcher("broken", "throw new RuntimeException('predicate broken')", "never"));
        var provider = new MockProvider("default", matchers, 0, 0, 0.0);

        assertThatThrownBy(() -> provider.chat(request("mock/anything", "hi")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Mock matcher predicate evaluation failed")
                .hasMessageContaining("predicate broken");
    }

    @Test
    void matchers_streamingPathUsesMatcherResponseToo() {
        List<CompiledMatcher> matchers = List.of(
                matcher("stream-target", "request.model == 'mock/stream'", "first second third fourth"));
        var provider = new MockProvider("default", matchers, 0, 0, 0.0);

        Iterator<SseChunk> stream = provider.streamChat(request("mock/stream", "hi"));
        StringBuilder joined = new StringBuilder();
        while (stream.hasNext()) {
            SseChunk chunk = stream.next();
            if (chunk.getDelta() != null) {
                joined.append(chunk.getDelta());
            }
        }
        assertThat(joined.toString().trim()).isEqualTo("first second third fourth");
    }

    @Test
    void matchers_emptyListFallsStraightThrough() {
        // Explicitly passing empty matchers list should behave identically to not having matchers.
        var provider = new MockProvider("default response", List.of(), 0, 0, 0.0);

        ChatResponse resp = provider.chat(request("mock/anything", "hi"));
        assertThat(resp.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("default response");
    }

    // -------------------------------------------------------------------------
    // File scenarios — scenario + YAML precedence
    // -------------------------------------------------------------------------

    /**
     * Creates a ScenarioMatcher directly from closures, so these tests exercise
     * MockProvider without going through the file loader.
     */
    private ScenarioMatcher scenario(String name, String whenSource, String respondText) {
        com.dvarahq.core.model.ChatRequest dummy = ChatRequest.builder().model("x").messages(List.of()).build();
        groovy.lang.Binding binding = new groovy.lang.Binding();
        new groovy.lang.GroovyShell(binding).evaluate(
                "when = " + whenSource + "\n" +
                "respond = { req -> '" + respondText + "' }\n");
        groovy.lang.Closure<?> whenClosure = (groovy.lang.Closure<?>) binding.getVariable("when");
        groovy.lang.Closure<?> respondClosure = (groovy.lang.Closure<?>) binding.getVariable("respond");
        return new ScenarioMatcher(name, "test:" + name, whenClosure, respondClosure);
    }

    @Test
    void fileScenarios_takePrecedenceOverYamlMatchers() {
        // A YAML matcher and a file scenario both match the same request — file scenario must win
        List<CompiledMatcher> yamlMatchers = List.of(
                matcher("from-yaml", "request.model == 'mock/shared'", "yaml response"));
        var provider = new MockProvider("default", yamlMatchers, 0, 0, 0.0);

        provider.replaceFileScenarios(List.of(
                scenario("from-scenario", "{ req -> req.model == 'mock/shared' }", "scenario response")));

        ChatResponse resp = provider.chat(request("mock/shared", "hi"));
        assertThat(resp.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("scenario response");
    }

    @Test
    void fileScenarios_fallThroughToYamlMatcherWhenNoScenarioMatches() {
        List<CompiledMatcher> yamlMatchers = List.of(
                matcher("yaml-only", "request.model == 'mock/yaml-only'", "from yaml"));
        var provider = new MockProvider("default", yamlMatchers, 0, 0, 0.0);

        provider.replaceFileScenarios(List.of(
                scenario("never-matches", "{ req -> req.model == 'mock/never-matches' }", "scenario")));

        ChatResponse resp = provider.chat(request("mock/yaml-only", "hi"));
        assertThat(resp.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("from yaml");
    }

    @Test
    void fileScenarios_replaceFileScenariosSwapsListAtomically() {
        // First activation: one scenario. Second activation: different scenario.
        var provider = new MockProvider("default", List.of(), 0, 0, 0.0);

        provider.replaceFileScenarios(List.of(
                scenario("first-scenario", "{ req -> true }", "first text")));
        ChatResponse first = provider.chat(request("mock/any", "hi"));
        assertThat(first.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("first text");

        provider.replaceFileScenarios(List.of(
                scenario("second-scenario", "{ req -> true }", "second text")));
        ChatResponse second = provider.chat(request("mock/any", "hi"));
        assertThat(second.getChoices().get(0).getMessage().getContent().get(0).toString())
                .contains("second text");
    }

    @Test
    void fileScenarios_emptyReplacementRemovesScenarios() {
        var provider = new MockProvider("default text", List.of(), 0, 0, 0.0);

        provider.replaceFileScenarios(List.of(
                scenario("temporary", "{ req -> true }", "temporary scenario text")));
        assertThat(provider.chat(request("mock/any", "hi")).getChoices().get(0).getMessage()
                .getContent().get(0).toString()).contains("temporary scenario text");

        provider.replaceFileScenarios(List.of());
        assertThat(provider.chat(request("mock/any", "hi")).getChoices().get(0).getMessage()
                .getContent().get(0).toString()).contains("default text");
    }
}