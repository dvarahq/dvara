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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.providers.openai.OpenAiProvider;
import com.dvarahq.server.v1.dto.ChatCompletionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A chat request with tools and a reasoning effort, on a route to OpenAI, goes to OpenAI's Responses API, which
 * Chat Completions sends those to for OpenAI's newest models ("Function tools with reasoning_effort are not
 * supported ... in /v1/chat/completions. To use function tools, use /v1/responses"). The requests are OpenCode's
 * and Copilot CLI's, captured live and redacted; the answers are read as the application reads them.
 */
class ChatViaResponsesTest {

    private MockRestServiceServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        // The application's own default converters: its JSON library reads the answers.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiProvider(key -> Optional.of("test-key"), "https://api.openai.com/v1", builder);
    }

    private static ChatRequest captured(String name) throws Exception {
        try (java.io.InputStream in = ChatViaResponsesTest.class.getResourceAsStream("/chat-clients/" + name)) {
            return ChatCompletionController.toInternal(JsonMapper.instance().readValue(in, ChatCompletionRequest.class));
        }
    }

    private static final String SSE = """
            event: response.created
            data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1","status":"in_progress","output":[]}}

            event: response.output_item.added
            data: {"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"bash","arguments":"","status":"in_progress"}}

            event: response.function_call_arguments.delta
            data: {"type":"response.function_call_arguments.delta","sequence_number":2,"item_id":"fc_1","output_index":0,"delta":"{\\"command\\":\\"ls\\"}"}

            event: response.output_item.done
            data: {"type":"response.output_item.done","sequence_number":3,"output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"bash","arguments":"{\\"command\\":\\"ls\\"}","status":"completed"}}

            event: response.completed
            data: {"type":"response.completed","sequence_number":4,"response":{"id":"resp_1","status":"completed","usage":{"input_tokens":6000,"input_tokens_details":{"cached_tokens":5760,"cache_write_tokens":0},"output_tokens":40,"output_tokens_details":{"reasoning_tokens":24},"total_tokens":6040}}}

            """;

    @Test
    void openCodesRequest_goesToResponses_andItsCallComesBackAsChunks() throws Exception {
        ChatRequest request = captured("opencode-tools-effort.json");
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.reasoning.effort").value("medium"))
                .andExpect(jsonPath("$.stream").value(true))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.input[0].role").value("system"))
                .andExpect(jsonPath("$.input[1].role").value("user"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].name").exists())
                .andExpect(jsonPath("$.tools[0].function").doesNotExist())
                .andExpect(jsonPath("$.max_output_tokens").value(32000))
                .andExpect(jsonPath("$.messages").doesNotExist())
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess(SSE, MediaType.TEXT_EVENT_STREAM));

        List<SseChunk> chunks = new ArrayList<>();
        provider.streamChat(request).forEachRemaining(chunks::add);

        server.verify();
        StringBuilder args = new StringBuilder();
        chunks.stream().filter(c -> c.getToolCalls() != null).flatMap(c -> c.getToolCalls().stream())
                .forEach(f -> args.append(f.argumentsFragment() == null ? "" : f.argumentsFragment()));
        assertThat(args.toString()).isEqualTo("{\"command\":\"ls\"}");
        SseChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isDone()).isTrue();
        assertThat(last.getFinishReason()).isEqualTo("tool_calls");
        assertThat(last.getUsage().getCachedInputTokens()).isEqualTo(5760);
        assertThat(last.getUsage().getReasoningTokens()).isEqualTo(24);
    }

    @Test
    void copilotsRequest_sendsItsCustomPatchTool_andAWholeAnswerComesBack() throws Exception {
        ChatRequest request = captured("copilot-tools-effort.json").toBuilder().stream(false).build();
        assertThat(request.getTools()).anyMatch(ToolDefinition::isCustom);
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.tools[?(@.type == 'custom')].name").value("apply_patch"))
                .andExpect(jsonPath("$.tools[?(@.type == 'custom')].format.syntax").value("lark"))
                .andExpect(jsonPath("$.temperature").value(1.0))
                .andExpect(content().string(containsString("\"reasoning\":{\"effort\":\"medium\"}")))
                .andRespond(withSuccess("""
                        {"id":"resp_2","object":"response","created_at":1,"status":"completed","model":"gpt-5.4-mini",
                         "output":[{"type":"reasoning","id":"rs_1","summary":[]},
                                   {"type":"message","id":"msg_1","status":"completed","role":"assistant",
                                    "content":[{"type":"output_text","text":"Patching.","annotations":[]}]},
                                   {"type":"custom_tool_call","id":"ctc_1","call_id":"call_9","name":"apply_patch","input":"*** Begin Patch","status":"completed"}],
                         "usage":{"input_tokens":9000,"input_tokens_details":{"cached_tokens":0,"cache_write_tokens":9000},
                                  "output_tokens":50,"output_tokens_details":{"reasoning_tokens":30},"total_tokens":9050}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(request);

        server.verify();
        MultimodalMessage message = response.getChoices().get(0).getMessage();
        assertThat(message.textContent()).isEqualTo("Patching.");
        assertThat(message.getToolCalls()).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("apply_patch");
            assertThat(ToolDefinition.customInput(c.getArguments())).isEqualTo("*** Begin Patch");
        });
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
        assertThat(response.getUsage().getCacheWriteTokens()).isEqualTo(9000);
        assertThat(response.getUsage().getReasoningTokens()).isEqualTo(30);
    }

    @Test
    void aToolCallAndItsResult_inTheHistory_areResponsesItems() throws Exception {
        ChatRequest request = captured("opencode-tools-effort.json");
        List<MultimodalMessage> history = new ArrayList<>(request.getMessages());
        history.add(MultimodalMessage.builder().role("assistant").content(List.of())
                .toolCalls(List.of(new com.dvarahq.core.model.ToolCall("call_1", "bash", "{\"command\":\"ls\"}"))).build());
        history.add(MultimodalMessage.toolResult("call_1", "README.md"));
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.input[2].type").value("function_call"))
                .andExpect(jsonPath("$.input[2].call_id").value("call_1"))
                .andExpect(jsonPath("$.input[2].arguments").value("{\"command\":\"ls\"}"))
                .andExpect(jsonPath("$.input[3].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[3].output").value("README.md"))
                .andRespond(withSuccess(SSE, MediaType.TEXT_EVENT_STREAM));

        provider.streamChat(request.toBuilder().messages(history).build()).forEachRemaining(c -> { });

        server.verify();
    }

    @Test
    void withoutTools_orWithAnEffortOfNone_itIsChatCompletionsAsBefore() throws Exception {
        String chat = """
                {"id":"c","object":"chat.completion","created":1,"model":"gpt-5.4-mini",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
                """;
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withSuccess(chat, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.openai.com/v1/chat/completions")).andRespond(withSuccess(chat, MediaType.APPLICATION_JSON));
        ChatRequest withTools = captured("opencode-tools-effort.json").toBuilder().stream(false).build();

        provider.chat(withTools.toBuilder().tools(null).toolChoice(null).build());
        provider.chat(withTools.toBuilder().reasoningEffort("none").build());

        server.verify();
    }

    @Test
    void aFieldTheResponsesApiHasNoPlaceFor_isRefused() throws Exception {
        ChatRequest request = captured("opencode-tools-effort.json").toBuilder().stop(List.of("END")).build();

        assertThatThrownBy(() -> provider.streamChat(request))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("stop");
    }
}
