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
package com.dvarahq.server.v1;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/responses} through the assembled controller, on the same
 * {@code ChatExecutionService}-backed line chat uses. These tests check that the Responses doorway
 * gets governance, metering and streaming from that line without re-testing the pipeline itself.
 */
@WebMvcTest(ResponsesController.class)
@Import(TestMetricsConfig.class)
class ResponsesControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean ProviderDispatcher dispatcher;
    @MockitoBean RequestPipeline requestPipeline;
    @MockitoBean ResponseCache responseCache;
    @MockitoBean com.dvarahq.core.metering.TokenUsageRepository tokenUsageRepository;
    @MockitoBean CostCalculationService costCalculationService;
    @MockitoBean CostEstimator costEstimator;
    @MockitoBean PiiEnforcer piiEnforcer;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean StreamingResponseEnforcer streamingResponseEnforcer;
    @MockitoBean AuditWriter auditWriter;
    @MockitoBean PriorityAdmissionController priorityAdmissionController;
    @MockitoBean com.dvarahq.core.metering.WorkspaceUsageListener usageListener;
    // The outcome listener is a bean the slice does not carry; what it is told is checked by
    // StreamingCallOutcomeTest.
    @MockitoBean com.dvarahq.core.metering.CallOutcomeListener outcomeListener;

    @BeforeEach
    void setUp() {
        when(responseCache.get(any())).thenReturn(Optional.empty());
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(requestPipeline.postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(piiEnforcer.stripForCache(any(ChatRequest.class), any())).thenAnswer(i -> i.getArgument(0));
        when(piiEnforcer.detokenizeResponse(any(ChatResponse.class), any(ChatRequest.class), any())).thenAnswer(i -> i.getArgument(0));
        when(streamingResponseEnforcer.wrap(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(streamingResponseEnforcer.wrap(any(), any(), any())).thenAnswer(i -> i.getArgument(0));
    }

    // -------- sync --------

    @Test
    void sync_cacheHit_stillEmitsThisRequestsGovernanceHeader() throws Exception {
        // detokenize adds the X-Gateway-Pii-Unresolved signal after the cache read, so a cache hit
        // must still emit gateway headers. A cached response is older, so its tokens are the more
        // likely to have been erased, and nobody is watching that path.
        when(responseCache.get(any())).thenReturn(Optional.of(chatResponse("gpt-4o", "Paris")));
        when(piiEnforcer.detokenizeResponse(any(ChatResponse.class), any(ChatRequest.class), any()))
                .thenAnswer(i -> ((ChatResponse) i.getArgument(0)).toBuilder()
                        .gatewayHeaders(java.util.Map.of("X-Gateway-Pii-Unresolved", "1"))
                        .build());

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "Capital of France?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andExpect(header().string("X-Gateway-Pii-Unresolved", "1"));
    }

    @Test
    void sync_returnsResponsesShape() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "Paris"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "Capital of France?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.object").value("response"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.output[0].type").value("message"))
                .andExpect(jsonPath("$.output[0].role").value("assistant"))
                .andExpect(jsonPath("$.output[0].content[0].type").value("output_text"))
                .andExpect(jsonPath("$.output[0].content[0].text").value("Paris"))
                .andExpect(jsonPath("$.usage.input_tokens").value(10))
                .andExpect(jsonPath("$.usage.output_tokens").value(3))
                .andExpect(jsonPath("$.usage.total_tokens").value(13));
    }

    @Test
    void sync_instructionsAndMessageArrayInput() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "instructions": "Be terse.",
                                 "input": [{"role": "user", "content": "hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output[0].content[0].text").value("ok"));
    }

    // -------- validation / unsupported --------

    @Test
    void missingModel_returns400() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input": "hi"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void store_true_returns400Unsupported() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "store": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    @Test
    void previousResponseId_returns400Unsupported() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "previous_response_id": "resp_1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    @Test
    void tools_returns400Unsupported() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi",
                                 "tools": [{"type": "web_search"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    /** Codex CLI sends tool_choice "auto" on every request, with or without tools. */
    @Test
    void toolChoiceAuto_isAccepted() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "tool_choice": "auto"}
                                """))
                .andExpect(status().isOk());
    }

    /** The dispatcher is stubbed to succeed, so a request let through fails on 200-vs-400. */
    @Test
    void include_returns400Unsupported() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi",
                                 "include": ["message.output_text.logprobs"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    /**
     * An empty include array asks for nothing, so it must not trip the gate. The {@code tools}
     * check treats an empty array the same way.
     */
    @Test
    void emptyInclude_isNotRejected() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "include": []}
                                """))
                .andExpect(status().isOk());
    }

    /** A built-in tool next to a function tool is refused, naming the built-in tool. */
    @Test
    void aBuiltInToolNextToAFunctionTool_isRefusedNamingIt() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi",
                                 "tools": [{"type": "function", "name": "f", "parameters": {"type": "object"}},
                                           {"type": "code_interpreter", "container": {"type": "auto"}}],
                                 "tool_choice": "auto"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("code_interpreter")));
    }

    // -------- function calling --------

    private static final String WEATHER_REQUEST = """
            {"model": "gpt-4o", %s"input": "Weather in Paris?",
             "tools": [{"type": "function", "name": "get_weather", "description": "Weather for a city",
                        "parameters": {"type": "object", "properties": {"city": {"type": "string"}}},
                        "strict": false}],
             "tool_choice": "auto"}
            """;

    @Test
    void sync_aFunctionCallComesBackAsAFunctionCallItem() throws Exception {
        MultimodalMessage call = MultimodalMessage.builder().role("assistant").content(List.of())
                .toolCalls(List.of(new com.dvarahq.core.model.ToolCall("call_1", "get_weather", "{\"city\":\"Paris\"}")))
                .build();
        when(dispatcher.chat(any())).thenReturn(ChatResponse.builder()
                .id("chatcmpl-x").model("gpt-4o").created(1704067200L)
                .choices(List.of(ChatResponse.Choice.builder().index(0).message(call).finishReason("tool_calls").build()))
                .usage(ChatResponse.Usage.builder().promptTokens(10).completionTokens(3).totalTokens(13).build())
                .build());

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WEATHER_REQUEST.formatted("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output.length()").value(1))
                .andExpect(jsonPath("$.output[0].type").value("function_call"))
                .andExpect(jsonPath("$.output[0].call_id").value("call_1"))
                .andExpect(jsonPath("$.output[0].name").value("get_weather"))
                .andExpect(jsonPath("$.output[0].arguments").value("{\"city\":\"Paris\"}"))
                .andExpect(jsonPath("$.output[0].status").value("completed"));

        // The provider was asked with the tool and the choice.
        verify(dispatcher).chat(argThat(r -> r.getTools() != null && r.getTools().size() == 1
                && "get_weather".equals(r.getTools().get(0).getName()) && "auto".equals(r.getToolChoice())));
    }

    @Test
    void streaming_aFunctionCallStreamsAsTypedEvents() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.open(0, "call_1", "get_weather", null))).build(),
                SseChunk.builder().id("c1").model("gpt-4o")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.arguments(0, "{\"city\":"))).build(),
                SseChunk.builder().id("c1").model("gpt-4o")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.arguments(0, "\"Paris\"}"))).build(),
                SseChunk.builder().id("c1").model("gpt-4o").finishReason("tool_calls").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(WEATHER_REQUEST.formatted("\"stream\": true, "));

        assertThat(events).extracting(e -> e.get("type").asText()).containsExactly(
                "response.created", "response.in_progress",
                "response.output_item.added",
                "response.function_call_arguments.delta", "response.function_call_arguments.delta",
                "response.function_call_arguments.done",
                "response.output_item.done",
                "response.completed");
        com.fasterxml.jackson.databind.JsonNode added = events.get(2);
        assertThat(added.get("output_index").asInt()).isZero();
        assertThat(added.at("/item/type").asText()).isEqualTo("function_call");
        assertThat(added.at("/item/call_id").asText()).isEqualTo("call_1");
        assertThat(added.at("/item/name").asText()).isEqualTo("get_weather");
        assertThat(added.at("/item/arguments").asText()).isEmpty();
        assertThat(added.at("/item/status").asText()).isEqualTo("in_progress");
        String itemId = added.at("/item/id").asText();
        assertThat(itemId).startsWith("fc_");

        assertThat(events.get(3).get("item_id").asText()).isEqualTo(itemId);
        assertThat(events.get(3).get("delta").asText()).isEqualTo("{\"city\":");
        assertThat(events.get(5).get("arguments").asText()).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(events.get(6).at("/item/arguments").asText()).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(events.get(6).at("/item/status").asText()).isEqualTo("completed");
        com.fasterxml.jackson.databind.JsonNode output = events.get(7).at("/response/output");
        assertThat(output).hasSize(1);
        assertThat(output.get(0).get("type").asText()).isEqualTo("function_call");
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).get("sequence_number").asInt()).isEqualTo(i);
        }
    }

    @Test
    void streaming_textThenAFunctionCall_finishesTheMessageFirst() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Checking.").build(),
                SseChunk.builder().id("c1").model("gpt-4o")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.open(0, "call_1", "get_weather", "{}"))).build(),
                SseChunk.builder().id("c1").model("gpt-4o").finishReason("tool_calls").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(WEATHER_REQUEST.formatted("\"stream\": true, "));

        List<String> types = events.stream().map(e -> e.get("type").asText()).toList();
        assertThat(types.indexOf("response.output_text.done"))
                .isLessThan(types.lastIndexOf("response.output_item.added"));
        com.fasterxml.jackson.databind.JsonNode output = events.get(events.size() - 1).at("/response/output");
        assertThat(output).hasSize(2);
        assertThat(output.get(0).get("type").asText()).isEqualTo("message");
        assertThat(output.get(0).at("/content/0/text").asText()).isEqualTo("Checking.");
        assertThat(output.get(1).get("type").asText()).isEqualTo("function_call");
        assertThat(output.get(1).get("arguments").asText()).isEqualTo("{}");
    }

    // -------- requests as Codex CLI builds them (fixtures from its source, not a live capture) --------

    private String fixture(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/codex/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void codexFirstTurn_isServedAndTheCallStreamsBack() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("team-coder")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.open(0, "call_1", "exec_command", "{\"cmd\":\"ls\"}"))).build(),
                SseChunk.builder().id("c1").model("team-coder").finishReason("tool_calls").done(true)
                        .usage(new ChatResponse.Usage(120, 9, 129)).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(fixture("first-turn.json"));

        org.mockito.ArgumentCaptor<ChatRequest> sent = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).streamChat(sent.capture());
        ChatRequest r = sent.getValue();
        assertThat(r.getMessages()).extracting(MultimodalMessage::getRole).containsExactly("system", "user", "user");
        assertThat(r.getTools()).extracting(com.dvarahq.core.model.ToolDefinition::getName)
                .containsExactly("exec_command", "write_stdin", "update_plan");
        assertThat(r.getToolChoice()).isEqualTo("auto");
        assertThat(r.getPromptCacheKey()).isEqualTo("0199d6a2-3b1c-7c40-9a51-5f0e8d2b7c11");
        assertThat(r.getReasoningEffort()).isNull();

        com.fasterxml.jackson.databind.JsonNode done = events.stream()
                .filter(e -> "response.output_item.done".equals(e.get("type").asText())).findFirst().orElseThrow();
        assertThat(done.at("/item/type").asText()).isEqualTo("function_call");
        assertThat(done.at("/item/call_id").asText()).isEqualTo("call_1");
        assertThat(done.at("/item/arguments").asText()).isEqualTo("{\"cmd\":\"ls\"}");
        com.fasterxml.jackson.databind.JsonNode completed = events.get(events.size() - 1);
        assertThat(completed.get("type").asText()).isEqualTo("response.completed");
        assertThat(completed.at("/response/usage/input_tokens").asInt()).isEqualTo(120);
        assertThat(completed.at("/response/usage/output_tokens").asInt()).isEqualTo(9);
    }

    @Test
    void codexToolTurn_sendsTheCallAndItsOutput_andLeavesOutTheReasoningItem() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("team-coder").delta("Three files.").finishReason("stop").done(true).build())
                .iterator());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(fixture("tool-turn.json"));

        org.mockito.ArgumentCaptor<ChatRequest> sent = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).streamChat(sent.capture());
        List<MultimodalMessage> messages = sent.getValue().getMessages();
        assertThat(messages).extracting(MultimodalMessage::getRole).containsExactly("system", "user", "assistant", "tool");
        assertThat(messages.get(2).getToolCalls()).singleElement()
                .satisfies(c -> assertThat(c.getId()).isEqualTo("call_1"))
                .satisfies(c -> assertThat(c.getArguments()).isEqualTo("{\"cmd\":\"ls\"}"));
        assertThat(messages.get(3).getToolCallId()).isEqualTo("call_1");
        assertThat(messages.get(3).textContent()).contains("pom.xml");
        assertThat(events.get(events.size() - 1).at("/response/output/0/content/0/text").asText()).isEqualTo("Three files.");
    }

    @Test
    void codexOnAnOpenAiCatalogModel_isRefusedForWebSearch_namingTheSetting() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fixture("openai-model-turn.json")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("web_search = \"disabled\"")));
    }

    /** Codex with web search off: its freeform apply_patch tool is accepted, and a call to it streams back as one. */
    @Test
    void codexOnAnOpenAiCatalogModel_withWebSearchOff_getsItsPatchCallBack() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode body =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(fixture("openai-model-turn.json"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) body.get("tools")).remove(2);
        String patch = "*** Begin Patch\n*** End Patch";
        String args = com.dvarahq.core.model.ToolDefinition.customArguments(patch);
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("gpt-5.5")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.open(0, "call_9", "apply_patch", args.substring(0, 5)))).build(),
                SseChunk.builder().id("c1").model("gpt-5.5")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.arguments(0, args.substring(5)))).build(),
                SseChunk.builder().id("c1").model("gpt-5.5").finishReason("tool_calls").done(true).build()).iterator());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(json.writeValueAsString(body));

        verify(dispatcher).streamChat(argThat(r -> r.getTools().stream().anyMatch(t -> t.isCustom() && "apply_patch".equals(t.getName()))));
        assertThat(events).extracting(e -> e.get("type").asText()).containsSubsequence(
                "response.output_item.added", "response.custom_tool_call_input.delta",
                "response.custom_tool_call_input.done", "response.output_item.done", "response.completed");
        assertThat(events).extracting(e -> e.get("type").asText()).doesNotContain("response.function_call_arguments.delta");
        com.fasterxml.jackson.databind.JsonNode done = events.stream()
                .filter(e -> "response.output_item.done".equals(e.get("type").asText())).findFirst().orElseThrow();
        assertThat(done.at("/item/type").asText()).isEqualTo("custom_tool_call");
        assertThat(done.at("/item/name").asText()).isEqualTo("apply_patch");
        assertThat(done.at("/item/call_id").asText()).isEqualTo("call_9");
        assertThat(done.at("/item/input").asText()).isEqualTo(patch);
    }

    @Test
    void streaming_aNamespacedCallComesBackUnderItsNameAndNamespace() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("m")
                        .toolCalls(List.of(com.dvarahq.core.model.ToolCallDelta.open(0, "call_1", "mcp__github__list_issues", "{}"))).build(),
                SseChunk.builder().id("c1").model("m").finishReason("tool_calls").done(true).build()).iterator());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents("""
                {"model": "m", "stream": true, "input": "issues?",
                 "tools": [{"type": "namespace", "name": "mcp__github", "description": "GitHub",
                            "tools": [{"type": "function", "name": "list_issues", "parameters": {"type": "object"}}]}]}
                """);

        com.fasterxml.jackson.databind.JsonNode done = events.stream()
                .filter(e -> "response.output_item.done".equals(e.get("type").asText())).findFirst().orElseThrow();
        assertThat(done.at("/item/type").asText()).isEqualTo("function_call");
        assertThat(done.at("/item/name").asText()).isEqualTo("list_issues");
        assertThat(done.at("/item/namespace").asText()).isEqualTo("mcp__github");
    }

    @Test
    void codexOnAnOpenAiCatalogModel_withoutBuiltInTools_carriesEffortAndVerbosity() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode body =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(fixture("openai-model-turn.json"));
        com.fasterxml.jackson.databind.node.ArrayNode tools = (com.fasterxml.jackson.databind.node.ArrayNode) body.get("tools");
        tools.remove(2);
        tools.remove(1);
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("gpt-5.5").delta("ok").finishReason("stop").done(true).build()).iterator());

        streamEvents(json.writeValueAsString(body));

        verify(dispatcher).streamChat(argThat(r -> "medium".equals(r.getReasoningEffort())
                && "low".equals(r.getVerbosity()) && r.getTools().size() == 1));
    }

    // -------- a route to OpenAI: the body sent to OpenAI's own Responses API --------

    private static final String OPENAI_ANSWER = """
            {"id":"resp_9","object":"response","created_at":1760000000,"status":"completed","model":"gpt-5.5",
             "output":[{"type":"reasoning","id":"rs_2","summary":[],"encrypted_content":"gAAAAAB-next"},
                       {"type":"message","id":"msg_1","status":"completed","role":"assistant",
                        "content":[{"type":"output_text","text":"Mail ann@example.com","annotations":[]}]}],
             "usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":1024},
                      "output_tokens":80,"output_tokens_details":{"reasoning_tokens":64},"total_tokens":1280}}
            """;

    @Test
    void direct_theGovernedInputIsWrittenBackIntoTheBody_andEncryptedReasoningGoesOnAsItCame() throws Exception {
        // Input governance (a redaction) changes the user's text; the body sent on carries the change in place.
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class))).thenAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            List<MultimodalMessage> messages = new java.util.ArrayList<>(r.getMessages());
            messages.set(1, MultimodalMessage.builder().role("user")
                    .content(List.of(new com.dvarahq.core.model.ContentBlock.TextBlock("List the files in [REDACTED]."))).build());
            return r.toBuilder().messages(messages).build();
        });
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("team-coder").delta("ok").finishReason("stop").done(true).build()).iterator());

        streamEvents(fixture("tool-turn.json").replace("List the files in this folder.", "List the files in /home/ann."));

        org.mockito.ArgumentCaptor<ChatRequest> sent = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).streamChat(sent.capture());
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                sent.getValue().getOpenAiResponses().governedJson(sent.getValue(), "gpt-5.5", true));
        assertThat(body.at("/input/1/content/0/text").asText()).isEqualTo("List the files in [REDACTED].");
        assertThat(body.at("/input/2/type").asText()).isEqualTo("reasoning");
        assertThat(body.at("/input/2/encrypted_content").asText()).isEqualTo("gAAAAAB-opaque");
        assertThat(body.at("/input/3/arguments").asText()).isEqualTo("{\"cmd\":\"ls\"}");
        assertThat(body.at("/client_metadata/session_id").asText()).isEqualTo("sess-1");
        assertThat(body.at("/model").asText()).isEqualTo("gpt-5.5");
    }

    @Test
    void direct_aPolicyRefusal_stopsTheCallBeforeAnyProvider() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new com.dvarahq.core.exception.GatewayException("POLICY_DENIED", "model not allowed"));

        mockMvc.perform(post("/v1/responses").contentType(MediaType.APPLICATION_JSON).content(fixture("tool-turn.json")))
                .andExpect(status().is4xxClientError());

        verify(dispatcher, org.mockito.Mockito.never()).streamChat(any());
        verify(dispatcher, org.mockito.Mockito.never()).chat(any());
    }

    @Test
    void direct_sync_returnsOpenAisOwnResponse_withTheGovernedOutputWrittenBack() throws Exception {
        when(dispatcher.chat(any())).thenAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            com.fasterxml.jackson.databind.node.ObjectNode answer =
                    com.dvarahq.core.model.OpenAiResponsesBody.parseResponse(OPENAI_ANSWER);
            r.getOpenAiResponses().upstreamResponse(answer);
            return ChatResponse.builder().id("resp_9").model("gpt-5.5").created(1760000000L)
                    .choices(List.of(ChatResponse.Choice.builder().index(0)
                            .message(com.dvarahq.core.model.OpenAiResponsesBody.responseMessage(answer))
                            .finishReason("stop").build()))
                    .usage(com.dvarahq.core.model.OpenAiResponsesBody.usage(answer.get("usage")))
                    .build();
        });
        // Output governance redacts the address.
        when(requestPipeline.postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class)))
                .thenAnswer(inv -> {
                    ChatResponse r = inv.getArgument(1);
                    return r.toBuilder().choices(List.of(ChatResponse.Choice.builder().index(0)
                            .message(MultimodalMessage.assistant("Mail [EMAIL]")).finishReason("stop").build())).build();
                });

        mockMvc.perform(post("/v1/responses").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-5.5", "input": "hi", "store": false,
                                 "include": ["reasoning.encrypted_content"], "reasoning": {"effort": "high"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("resp_9"))
                .andExpect(jsonPath("$.output[0].type").value("reasoning"))
                .andExpect(jsonPath("$.output[0].encrypted_content").value("gAAAAAB-next"))
                .andExpect(jsonPath("$.output[1].content[0].text").value("Mail [EMAIL]"))
                .andExpect(jsonPath("$.usage.input_tokens_details.cached_tokens").value(1024))
                .andExpect(jsonPath("$.usage.output_tokens_details.reasoning_tokens").value(64));
    }

    private static List<SseChunk> openAiEvents() {
        String[][] events = {
                {"response.created", "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_9\",\"status\":\"in_progress\",\"output\":[]}}"},
                {"response.output_item.done", "{\"type\":\"response.output_item.done\",\"sequence_number\":1,\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs_2\",\"summary\":[],\"encrypted_content\":\"gAAAAAB-next\"}}"},
                {"response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"sequence_number\":2,\"item_id\":\"msg_1\",\"output_index\":1,\"content_index\":0,\"delta\":\"One file.\"}"},
                {"response.completed", "{\"type\":\"response.completed\",\"sequence_number\":3,\"response\":{\"id\":\"resp_9\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":4},\"output_tokens\":5,\"output_tokens_details\":{\"reasoning_tokens\":3},\"total_tokens\":15}}}"}};
        List<SseChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < events.length; i++) {
            SseChunk.SseChunkBuilder c = SseChunk.builder().model("gpt-5.5")
                    .responsesEvent(new com.dvarahq.core.model.ResponsesEvent(events[i][0], events[i][1]));
            if (i == 2) c.delta("One file.");
            if (i == 3) c.done(true).finishReason("stop").usage(new ChatResponse.Usage(10, 5, 15, 4, 0, 3));
            chunks.add(c.build());
        }
        return chunks;
    }

    @Test
    void direct_stream_relaysOpenAisEventsAsTheyCame_andMetersItsUsage() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(openAiEvents().iterator());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(fixture("tool-turn.json"));

        assertThat(events).extracting(e -> e.get("type").asText()).containsExactly(
                "response.created", "response.output_item.done", "response.output_text.delta", "response.completed");
        assertThat(events.get(0).at("/response/id").asText()).isEqualTo("resp_9");
        assertThat(events.get(1).at("/item/encrypted_content").asText()).isEqualTo("gAAAAAB-next");
        verify(tokenUsageRepository, timeout(2000)).save(argThat(r -> r != null && !r.isEstimated()
                && r.getInputTokens() == 10 && r.getCachedInputTokens() == 4 && r.getReasoningTokens() == 3));
    }

    @Test
    void direct_stream_aCutOffEndsWithAFailureAfterTheRelayedEvents() throws Exception {
        List<SseChunk> first = openAiEvents().subList(0, 2);
        java.util.Iterator<SseChunk> cut = new java.util.Iterator<>() {
            int i;
            @Override public boolean hasNext() {
                if (i < first.size()) return true;
                throw new com.dvarahq.core.exception.GatewayException("PROVIDER_ERROR", "connection reset");
            }
            @Override public SseChunk next() { return first.get(i++); }
        };
        when(dispatcher.streamChat(any())).thenReturn(cut);

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(fixture("tool-turn.json"));

        assertThat(events).extracting(e -> e.get("type").asText())
                .containsExactly("response.created", "response.output_item.done", "response.failed");
        assertThat(events.get(2).get("sequence_number").asInt()).isEqualTo(2);
    }

    @Test
    void direct_stream_whenTheGuardHoldsContent_theAnswerIsRenderedAndTheReasoningKept() throws Exception {
        List<SseChunk> upstreamChunks = openAiEvents();
        // A guard that holds content: OpenAI's events with no content pass, the text comes later as its own chunk.
        class Held implements java.util.Iterator<SseChunk>, com.dvarahq.core.model.HeldDelivery {
            final java.util.Iterator<SseChunk> out = List.of(upstreamChunks.get(0), upstreamChunks.get(1),
                    SseChunk.builder().model("gpt-5.5").delta("One file.").build(),
                    SseChunk.builder().model("gpt-5.5").done(true).finishReason("stop")
                            .usage(new ChatResponse.Usage(10, 5, 15, 4, 0, 3)).build()).iterator();
            @Override public boolean hasNext() { return out.hasNext(); }
            @Override public SseChunk next() { return out.next(); }
            @Override public boolean holdsContent() { return true; }
        }
        when(dispatcher.streamChat(any())).thenReturn(upstreamChunks.iterator());
        when(streamingResponseEnforcer.wrap(any(), any(), any())).thenAnswer(i -> new Held());

        List<com.fasterxml.jackson.databind.JsonNode> events = streamEvents(fixture("tool-turn.json"));

        assertThat(events.get(0).get("type").asText()).isEqualTo("response.created");
        assertThat(events.get(0).at("/response/id").asText()).isNotEqualTo("resp_9");
        com.fasterxml.jackson.databind.JsonNode output = events.get(events.size() - 1).at("/response/output");
        assertThat(output.get(0).get("type").asText()).isEqualTo("reasoning");
        assertThat(output.get(0).get("encrypted_content").asText()).isEqualTo("gAAAAAB-next");
        assertThat(output.get(1).at("/content/0/text").asText()).isEqualTo("One file.");
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).get("sequence_number").asInt()).isEqualTo(i);
        }
    }

    /** The SSE events of a streamed request, each as its JSON. */
    private List<com.fasterxml.jackson.databind.JsonNode> streamEvents(String body) throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        String sse = mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<com.fasterxml.jackson.databind.JsonNode> events = new java.util.ArrayList<>();
        for (String line : sse.split("\n")) {
            if (line.startsWith("data:")) {
                events.add(json.readTree(line.substring(5).trim()));
            }
        }
        return events;
    }

    @Test
    void providerError_returns502() throws Exception {
        when(dispatcher.chat(any()))
                .thenThrow(new com.dvarahq.core.exception.GatewayException("PROVIDER_ERROR", "upstream 500"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("provider_error"));
    }

    // -------- streaming --------

    @Test
    void streaming_emitsTypedEventSequence() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Hel").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").delta("lo").finishReason("stop").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("response.created");
        assertThat(body).contains("response.output_item.added");
        assertThat(body).contains("response.content_part.added");
        assertThat(body).contains("response.output_text.delta");
        assertThat(body).contains("response.output_text.done");
        assertThat(body).contains("response.completed");
        assertThat(body).contains("\"sequence_number\":0");
        assertThat(body).contains("Hel");
        assertThat(body).contains("lo");
    }

    /**
     * A cancelled Responses request records no error and still runs its tail. Unlike the chat
     * doorway's twin, this does not check that cancellation is tested before hasNext(): this
     * doorway emits two events before the loop, and on a completed emitter those fail first, so
     * the loop is not reached either way. The loop is the same code as chat's and is checked there.
     */
    @Test
    void streaming_anAlreadyCancelledRequestNeverReadsUpstream() throws Exception {
        java.util.concurrent.CountDownLatch opening = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch clientLeft = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch tailRan = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger hasNextCalls = new java.util.concurrent.atomic.AtomicInteger();
        class Upstream implements java.util.Iterator<SseChunk>, AutoCloseable {
            @Override public boolean hasNext() { hasNextCalls.incrementAndGet(); return false; }
            @Override public SseChunk next() { throw new AssertionError("read upstream after cancellation"); }
            @Override public void close() { tailRan.countDown(); }
        }
        when(dispatcher.streamChat(any())).thenAnswer(inv -> {
            opening.countDown();
            clientLeft.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new Upstream();
        });

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(opening.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        mvcResult.getRequest().getAsyncContext().complete();
        clientLeft.countDown();

        assertThat(tailRan.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("the tail still ran").isTrue();
        assertThat(hasNextCalls.get()).as("cancellation is checked before any upstream read").isZero();
        assertThat(mvcResult.getRequest().getAttribute(com.dvarahq.server.web.AccessLogFilter.ATTR_ERROR_CODE)).isNull();
    }

    // The guard learns about cancellation only through close(), so the controller has to call it.
    @Test
    void streaming_closesTheGuardWhenTheStreamEnds() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").delta(null).finishReason("stop").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        class ClosableGuard implements java.util.Iterator<SseChunk>, AutoCloseable {
            private final java.util.Iterator<SseChunk> delegate;
            ClosableGuard(java.util.Iterator<SseChunk> delegate) { this.delegate = delegate; }
            @Override public boolean hasNext() { return delegate.hasNext(); }
            @Override public SseChunk next() { return delegate.next(); }
            @Override public void close() { closed.countDown(); }
        }
        when(streamingResponseEnforcer.wrap(any(), any(), any()))
                .thenAnswer(i -> new ClosableGuard(i.getArgument(0)));

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // The finally block runs on the virtual thread after the emitter completes.
        assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the controller must close the guard so cancellation can be finalized")
                .isTrue();
    }

    @Test
    void streaming_persistsTokenUsageRecord() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("streaming output text").finishReason("stop").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // Streaming Responses calls land an estimated token_usage row, as streaming chat does.
        verify(tokenUsageRepository, timeout(2000).times(1))
                .save(argThat(r -> r != null && "gpt-4o".equals(r.getModel())
                        && r.isEstimated() && r.getOutputTokens() > 0 && r.getInputTokens() > 0));
    }

    private static ChatResponse chatResponse(String model, String text) {
        return ChatResponse.builder()
                .id("chatcmpl-x").model(model).object("chat.completion").created(1704067200L)
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.assistant(text)).finishReason("stop").build()))
                .usage(ChatResponse.Usage.builder().promptTokens(10).completionTokens(3).totalTokens(13).build())
                .build();
    }

    /** A stream that ends before its finish is reported as failed, not completed. */
    @Test
    void streaming_aStreamThatEndsBeforeItsFinishIsReportedFailed() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Hel").done(false).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("response.failed");
        assertThat(body).doesNotContain("response.completed");
    }

    /** SDKs send Accept: text/event-stream with a streamed request; a refusal must still reach them as JSON. */
    @Test
    void aRefusedStreamedRequest_isItsJsonError_whenTheClientAcceptsOnlyEventStreams() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi", "store": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

}
