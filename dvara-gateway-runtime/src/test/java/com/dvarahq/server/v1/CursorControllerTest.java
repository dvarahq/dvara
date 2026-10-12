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
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /v1/cursor}: Cursor's base URL. The request fixtures follow public descriptions
 * of Cursor's traffic; a live Cursor session has not
 * been recorded yet.
 */
@WebMvcTest({CursorController.class, ChatCompletionController.class, ModelsController.class})
@Import(TestMetricsConfig.class)
class CursorControllerTest {

    @Autowired MockMvc mockMvc;

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

    private String fixture(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/cursor/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().id("c1").model("gateway-gpt").created(1)
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.assistant(text)).finishReason("stop").build()))
                .usage(new ChatResponse.Usage(10, 2, 12))
                .build();
    }

    @Test
    void chatMode_isServedAsChat_withCursorsMixedToolShapesPutRight() throws Exception {
        when(dispatcher.chat(any())).thenReturn(answer("summary"));

        mockMvc.perform(post("/v1/cursor/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "Cursor/1.0").content(fixture("chat-mode.json")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value("summary"));

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(sent.capture());
        ChatRequest r = sent.getValue();
        assertThat(r.getMessages()).extracting(MultimodalMessage::getRole).containsExactly("user", "assistant", "tool", "user");
        assertThat(r.getTools()).extracting(ToolDefinition::getName).containsExactly("read_file", "ApplyPatch");
        ToolDefinition patch = r.getTools().get(1);
        assertThat(patch.isCustom()).isTrue();
        assertThat(patch.getFormat()).containsEntry("syntax", "lark").containsEntry("definition", "start: patch");
        assertThat(r.getToolChoice()).isEqualTo(java.util.Map.of("type", "custom", "custom", java.util.Map.of("name", "ApplyPatch")));
        assertThat(r.getOpenAiResponses()).isNull();
    }

    @Test
    void agentMode_isReadAsTheResponsesApi_andAnsweredAsChat() throws Exception {
        when(dispatcher.chat(any())).thenReturn(answer("agent reply"));
        String body = fixture("agent-mode.json").replace("\"stream\": true", "\"stream\": false");

        mockMvc.perform(post("/v1/cursor/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value("agent reply"))
                .andExpect(jsonPath("$.output").doesNotExist());

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(sent.capture());
        ChatRequest r = sent.getValue();
        assertThat(r.getMessages()).extracting(MultimodalMessage::getRole).containsExactly("system", "user");
        assertThat(r.getReasoningEffort()).isEqualTo("medium");
        assertThat(r.getTools()).extracting(ToolDefinition::getName).containsExactly("read_file", "ApplyPatch");
        assertThat(r.getTools().get(1).isCustom()).isTrue();
        // What OpenAI's Responses API is sent: Cursor's chat-only fields taken out.
        var direct = new ObjectMapper().readTree(r.getOpenAiResponses().governedJson(r, "gpt-5.5", false));
        assertThat(direct.has("stream_options")).isFalse();
        assertThat(direct.has("messages")).isFalse();
        assertThat(direct.at("/tools/1/format/syntax").asText()).isEqualTo("lark");
    }

    @Test
    void agentMode_streams_inTheChatChunkShape_withTheUsageChunkItAskedFor() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("gateway-gpt").delta("Hel").build(),
                SseChunk.builder().id("c1").model("gateway-gpt").delta("lo").finishReason("stop").done(true)
                        .usage(new ChatResponse.Usage(10, 2, 12)).build()).iterator());

        MvcResult started = mockMvc.perform(post("/v1/cursor/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture("agent-mode.json")))
                .andExpect(request().asyncStarted()).andReturn();
        String sse = mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(sse).contains("\"object\":\"chat.completion.chunk\"").contains("\"content\":\"Hel\"")
                .contains("\"usage\":{\"prompt_tokens\":10").contains("[DONE]")
                .doesNotContain("response.created");
    }

    @Test
    void agentMode_aBuiltInTool_isRefusedWithAJsonError() throws Exception {
        String body = fixture("agent-mode.json").replace("{\"type\": \"function\", \"name\": \"read_file\"",
                "{\"type\": \"web_search\"}, {\"type\": \"function\", \"name\": \"read_file\"");

        mockMvc.perform(post("/v1/cursor/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
        verify(dispatcher, never()).streamChat(any());
    }

    @Test
    void models_isTheModelList() throws Exception {
        when(dispatcher.allProviders()).thenReturn(List.of());

        mockMvc.perform(get("/v1/cursor/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"));
    }

    @Test
    void theRequestIsCountedAsTheDoorwayReadsIt_beforeItIsAdmitted() throws Exception {
        var agent = AdmissionRequests.forTokenCount("/v1/cursor/chat/completions",
                fixture("agent-mode.json").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var chat = AdmissionRequests.forTokenCount("/v1/cursor/chat/completions",
                fixture("chat-mode.json").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(agent).isPresent();
        assertThat(agent.get().getMessages()).extracting(MultimodalMessage::textContent).contains("hello");
        assertThat(chat).isPresent();
        assertThat(chat.get().getMessages()).hasSize(4);
    }

    // Cursor's model-picker variants: -fast and -thinking-<effort> are read off the model name on this doorway only.

    private ChatRequest sentWhole(String path, String body) throws Exception {
        when(dispatcher.chat(any())).thenReturn(answer("ok"));
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(sent.capture());
        return sent.getValue();
    }

    private ChatRequest sentStreamed(String body) throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c1").model("gateway-gpt").delta("ok").finishReason("stop").done(true).build()).iterator());
        MvcResult started = mockMvc.perform(post("/v1/cursor/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).streamChat(sent.capture());
        return sent.getValue();
    }

    private static String model(String body, String model) {
        return body.replace("\"model\": \"gateway-gpt\"", "\"model\": \"" + model + "\"");
    }

    @Test
    void chatMode_aThinkingFastVariant_isRoutedByItsModel_withItsEffort() throws Exception {
        ChatRequest r = sentWhole("/v1/cursor/chat/completions", model(fixture("chat-mode.json"), "gateway-gpt-thinking-high-fast"));
        assertThat(r.getModel()).isEqualTo("gateway-gpt");
        assertThat(r.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void chatMode_streamed_aFastVariant_isRoutedByItsModel() throws Exception {
        String body = model(fixture("chat-mode.json"), "gateway-gpt-fast").replace("\"stream\": false", "\"stream\": true");
        ChatRequest r = sentStreamed(body);
        assertThat(r.getModel()).isEqualTo("gateway-gpt");
        assertThat(r.getReasoningEffort()).isNull();
    }

    @Test
    void agentMode_aThinkingVariant_setsReasoningEffort_overTheBodys() throws Exception {
        String body = model(fixture("agent-mode.json"), "gateway-gpt-thinking-low").replace("\"stream\": true", "\"stream\": false");
        ChatRequest r = sentWhole("/v1/cursor/chat/completions", body);
        assertThat(r.getModel()).isEqualTo("gateway-gpt");
        assertThat(r.getReasoningEffort()).isEqualTo("low");
        var direct = new ObjectMapper().readTree(r.getOpenAiResponses().governedJson(r, "gpt-5.5", false));
        assertThat(direct.at("/reasoning/effort").asText()).isEqualTo("low");
    }

    @Test
    void agentMode_streamed_aThinkingFastVariant_isRoutedByItsModel_withItsEffort() throws Exception {
        ChatRequest r = sentStreamed(model(fixture("agent-mode.json"), "gateway-gpt-thinking-xhigh-fast"));
        assertThat(r.getModel()).isEqualTo("gateway-gpt");
        assertThat(r.getReasoningEffort()).isEqualTo("xhigh");
    }

    @Test
    void aBareThinkingSuffix_staysPartOfTheModelName() throws Exception {
        String body = model(fixture("agent-mode.json"), "gateway-gpt-thinking").replace("\"stream\": true", "\"stream\": false");
        ChatRequest r = sentWhole("/v1/cursor/chat/completions", body);
        assertThat(r.getModel()).isEqualTo("gateway-gpt-thinking");
        assertThat(r.getReasoningEffort()).isEqualTo("medium");
    }

    @Test
    void theChatDoorway_leavesAVariantNameAsItIs() throws Exception {
        ChatRequest r = sentWhole("/v1/chat/completions",
                "{\"model\": \"gateway-gpt-thinking-high-fast\", \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}");
        assertThat(r.getModel()).isEqualTo("gateway-gpt-thinking-high-fast");
        assertThat(r.getReasoningEffort()).isNull();
    }
}
