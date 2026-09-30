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
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/messages} through the assembled controller, on the same execution line as chat.
 * These check the translation both ways and Anthropic's stream events; the governance line itself is
 * tested where it lives.
 */
@WebMvcTest(MessagesController.class)
@Import(TestMetricsConfig.class)
class MessagesControllerTest {

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

    private static MockHttpServletRequestBuilder messages(String body) {
        return post("/v1/messages")
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    // -------- non-streaming --------

    @Test
    void aTextReplyComesBackAsAnAnthropicMessage() throws Exception {
        when(dispatcher.chat(any())).thenReturn(reply("Paris", "stop",
                ChatResponse.Usage.builder().promptTokens(100).completionTokens(3).totalTokens(103)
                        .cachedInputTokens(60).cacheWriteTokens(10).build()));

        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100,
                         "messages": [{"role": "user", "content": "Capital of France?"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content[0].type").value("text"))
                .andExpect(jsonPath("$.content[0].text").value("Paris"))
                .andExpect(jsonPath("$.stop_reason").value("end_turn"))
                // Anthropic counts fresh input apart from cache reads and writes.
                .andExpect(jsonPath("$.usage.input_tokens").value(30))
                .andExpect(jsonPath("$.usage.cache_read_input_tokens").value(60))
                .andExpect(jsonPath("$.usage.cache_creation_input_tokens").value(10))
                .andExpect(jsonPath("$.usage.output_tokens").value(3));
    }

    @Test
    void theSystemPromptIsASystemMessageNotUserContent() throws Exception {
        when(dispatcher.chat(any())).thenReturn(reply("ok", "stop", null));

        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100,
                         "system": [{"type": "text", "text": "You are terse."}, {"type": "text", "text": "Answer in French."}],
                         "messages": [{"role": "user", "content": "hi"}]}
                        """))
                .andExpect(status().isOk());

        ChatRequest sent = sentRequest();
        assertThat(sent.getMessages().get(0).getRole()).isEqualTo("system");
        assertThat(sent.getMessages().get(0).textContent()).isEqualTo("You are terse.\n\nAnswer in French.");
        assertThat(sent.getMessages().get(1).getRole()).isEqualTo("user");
        assertThat(sent.getMessages().get(1).textContent()).isEqualTo("hi");
    }

    @Test
    void aToolTurnIsCarriedAcrossWithItsArgumentsAndResult() throws Exception {
        when(dispatcher.chat(any())).thenReturn(reply("done", "stop", null));

        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100,
                         "tools": [{"name": "read_file", "description": "Reads a file",
                                    "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}}}],
                         "tool_choice": {"type": "any"},
                         "messages": [
                           {"role": "user", "content": "Read a.txt"},
                           {"role": "assistant", "content": [
                              {"type": "text", "text": "Reading it."},
                              {"type": "tool_use", "id": "toolu_1", "name": "read_file", "input": {"path": "a.txt", "lines": [1, 2]}}]},
                           {"role": "user", "content": [
                              {"type": "tool_result", "tool_use_id": "toolu_1", "content": "hello"},
                              {"type": "tool_result", "tool_use_id": "toolu_2", "content": [{"type": "text", "text": "no such file"}], "is_error": true},
                              {"type": "text", "text": "Summarise it."}]}
                         ]}
                        """))
                .andExpect(status().isOk());

        ChatRequest sent = sentRequest();
        assertThat(sent.getTools()).singleElement().satisfies(t -> {
            assertThat(t.getName()).isEqualTo("read_file");
            assertThat(t.getParameters()).containsEntry("type", "object");
        });
        assertThat(sent.getToolChoice()).isEqualTo("required");
        List<MultimodalMessage> m = sent.getMessages();
        assertThat(m).extracting(MultimodalMessage::getRole).containsExactly("user", "assistant", "tool", "tool", "user");
        ToolCall call = m.get(1).getToolCalls().get(0);
        assertThat(call.getId()).isEqualTo("toolu_1");
        assertThat(call.getName()).isEqualTo("read_file");
        assertThat(call.getArguments()).isEqualTo("{\"path\":\"a.txt\",\"lines\":[1,2]}");
        assertThat(m.get(2).getToolCallId()).isEqualTo("toolu_1");
        assertThat(m.get(2).textContent()).isEqualTo("hello");
        assertThat(m.get(2).getToolError()).isNull();
        assertThat(m.get(3).getToolError()).isTrue();
        assertThat(m.get(4).textContent()).isEqualTo("Summarise it.");
    }

    @Test
    void aReplyWithAToolCallComesBackAsAToolUseBlock() throws Exception {
        ChatResponse withCall = ChatResponse.builder().id("c").model("claude-sonnet-4-5")
                .choices(List.of(ChatResponse.Choice.builder().index(0).finishReason("tool_calls")
                        .message(MultimodalMessage.builder().role("assistant")
                                .content(List.of(new ContentBlock.TextBlock("Let me look.")))
                                .toolCalls(List.of(ToolCall.builder().id("toolu_9").name("read_file")
                                        .arguments("{\"path\":\"b.txt\"}").build()))
                                .build()).build()))
                .build();
        when(dispatcher.chat(any())).thenReturn(withCall);

        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100,
                         "messages": [{"role": "user", "content": "Read b.txt"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("text"))
                .andExpect(jsonPath("$.content[1].type").value("tool_use"))
                .andExpect(jsonPath("$.content[1].id").value("toolu_9"))
                .andExpect(jsonPath("$.content[1].input.path").value("b.txt"))
                .andExpect(jsonPath("$.stop_reason").value("tool_use"));
    }

    // -------- refusals --------

    @Test
    void noAnthropicVersionIsRefusedWithAStableReason() throws Exception {
        mockMvc.perform(post("/v1/messages").contentType(MediaType.APPLICATION_JSON).content("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100, "messages": [{"role": "user", "content": "hi"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("unsupported_api_version"));
    }

    @Test
    void anUnknownAnthropicVersionIsRefused() throws Exception {
        mockMvc.perform(post("/v1/messages").header("anthropic-version", "2099-01-01")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100, "messages": [{"role": "user", "content": "hi"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_api_version"));
    }

    @Test
    void aFieldTheGatewayCannotCarryIsRefusedNotDropped() throws Exception {
        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100, "container": "c1",
                         "messages": [{"role": "user", "content": "hi"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("container")));
    }

    @Test
    void extendedThinkingIsRefused() throws Exception {
        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 2000, "thinking": {"type": "enabled", "budget_tokens": 1024},
                         "messages": [{"role": "user", "content": "hi"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    @Test
    void maxTokensIsRequired() throws Exception {
        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "messages": [{"role": "user", "content": "hi"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    /** A governance refusal on this doorway has the status and code it has on chat, in Anthropic's envelope. */
    @Test
    void aPiiBlockIsRefusedWithTheSameCodeAsOnChat() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("PII_DETECTED", "The request contains PII the policy blocks."));

        mockMvc.perform(messages("""
                        {"model": "claude-sonnet-4-5", "max_tokens": 100,
                         "messages": [{"role": "user", "content": "my SSN is 123-45-6789"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.code").value("pii_detected"));
    }

    // -------- streaming --------

    @Test
    void aStreamedTextReplyEmitsAnthropicEvents() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("claude-sonnet-4-5").delta("Hel").build(),
                SseChunk.builder().id("c1").model("claude-sonnet-4-5").delta("lo").finishReason("stop").done(true)
                        .usage(ChatResponse.Usage.builder().promptTokens(12).completionTokens(2).totalTokens(14).build())
                        .build()).iterator());

        List<Map<String, Object>> events = stream("""
                {"model": "claude-sonnet-4-5", "max_tokens": 100, "stream": true,
                 "messages": [{"role": "user", "content": "hi"}]}
                """);

        assertThat(events).extracting(e -> e.get("type")).containsExactly(
                "message_start", "ping", "content_block_start", "content_block_delta", "content_block_delta",
                "content_block_stop", "message_delta", "message_stop");
        assertThat(path(events.get(3), "delta", "text")).isEqualTo("Hel");
        assertThat(path(events.get(6), "delta", "stop_reason")).isEqualTo("end_turn");
        assertThat(path(events.get(6), "usage", "output_tokens")).isEqualTo(2);
        assertThat(path(events.get(6), "usage", "input_tokens")).isEqualTo(12);
    }

    @Test
    void aStreamedToolCallIsAToolUseBlockWithJsonDeltas() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").delta("Looking.").build(),
                SseChunk.builder().id("c1").toolCalls(List.of(ToolCallDelta.open(0, "toolu_1", "read_file", "{\"pa"))).build(),
                SseChunk.builder().id("c1").toolCalls(List.of(ToolCallDelta.arguments(0, "th\":\"a.txt\"}"))).build(),
                SseChunk.builder().id("c1").finishReason("tool_calls").done(true).build()).iterator());

        List<Map<String, Object>> events = stream("""
                {"model": "claude-sonnet-4-5", "max_tokens": 100, "stream": true,
                 "messages": [{"role": "user", "content": "read a.txt"}]}
                """);

        assertThat(events).extracting(e -> e.get("type")).containsExactly(
                "message_start", "ping",
                "content_block_start", "content_block_delta", "content_block_stop",
                "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                "message_delta", "message_stop");
        assertThat(path(events.get(5), "content_block", "type")).isEqualTo("tool_use");
        assertThat(path(events.get(5), "content_block", "id")).isEqualTo("toolu_1");
        assertThat(events.get(5).get("index")).isEqualTo(1);
        assertThat(path(events.get(6), "delta", "type")).isEqualTo("input_json_delta");
        assertThat((String) path(events.get(6), "delta", "partial_json") + path(events.get(7), "delta", "partial_json"))
                .isEqualTo("{\"path\":\"a.txt\"}");
        assertThat(path(events.get(9), "delta", "stop_reason")).isEqualTo("tool_use");
    }

    /** The streaming guard's refusal is a terminal chunk; on this doorway it ends the message as a refusal. */
    @Test
    void aStreamRefusedByTheGuardEndsWithARefusal() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").finishReason("content_filter").done(true).build()).iterator());

        List<Map<String, Object>> events = stream("""
                {"model": "claude-sonnet-4-5", "max_tokens": 100, "stream": true,
                 "messages": [{"role": "user", "content": "hi"}]}
                """);

        assertThat(events).extracting(e -> e.get("type")).containsExactly(
                "message_start", "ping", "message_delta", "message_stop");
        assertThat(path(events.get(2), "delta", "stop_reason")).isEqualTo("refusal");
    }

    @Test
    void aStreamThatEndsBeforeItsFinishEndsWithAnErrorEvent() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").delta("Hel").build()).iterator());

        List<Map<String, Object>> events = stream("""
                {"model": "claude-sonnet-4-5", "max_tokens": 100, "stream": true,
                 "messages": [{"role": "user", "content": "hi"}]}
                """);

        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("error");
        assertThat(events).extracting(e -> e.get("type")).doesNotContain("message_stop");
    }

    @Test
    void aStreamedCallIsMetered() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("claude-sonnet-4-5").delta("streaming output text")
                        .finishReason("stop").done(true).build()).iterator());

        stream("""
                {"model": "claude-sonnet-4-5", "max_tokens": 100, "stream": true,
                 "messages": [{"role": "user", "content": "hi"}]}
                """);

        verify(tokenUsageRepository, timeout(2000).times(1))
                .save(argThat(r -> r != null && "claude-sonnet-4-5".equals(r.getModel()) && r.getOutputTokens() > 0));
    }

    // -------- helpers --------

    private ChatRequest sentRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(captor.capture());
        return captor.getValue();
    }

    private List<Map<String, Object>> stream(String body) throws Exception {
        MvcResult started = mockMvc.perform(messages(body)).andExpect(request().asyncStarted()).andReturn();
        String sse = mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : sse.split("\n")) {
            if (line.startsWith("data:")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> event = JsonMapper.instance().readValue(line.substring(5).trim(), Map.class);
                events.add(event);
            }
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> event, String... keys) {
        Object at = event;
        for (String k : keys) {
            at = ((Map<String, Object>) at).get(k);
        }
        return at;
    }

    private static ChatResponse reply(String text, String finish, ChatResponse.Usage usage) {
        return ChatResponse.builder().id("c").model("claude-sonnet-4-5").object("chat.completion").created(1704067200L)
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.assistant(text)).finishReason(finish).build()))
                .usage(usage)
                .build();
    }
}
