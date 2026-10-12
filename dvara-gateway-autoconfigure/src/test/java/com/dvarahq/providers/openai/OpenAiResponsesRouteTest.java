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
package com.dvarahq.providers.openai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.OpenAiResponsesBody;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A Responses API request on a route to OpenAI goes to OpenAI's own {@code /responses} as the caller sent it:
 * unknown fields, custom tools and encrypted reasoning included. Its answer is read for governance, usage with
 * cached and reasoning tokens, and kept whole for the caller.
 */
class OpenAiResponsesRouteTest {

    /** A tool-result turn as Codex CLI builds it, with encrypted reasoning from the turn before. */
    static final String BODY = """
            {"model":"gpt-5.5","stream":true,"store":false,
             "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"List the files."}]},
                      {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"gAAAAAB-opaque"},
                      {"type":"function_call","id":"fc_1","name":"exec_command","arguments":"{\\"cmd\\":\\"ls\\"}","call_id":"call_1"},
                      {"type":"function_call_output","call_id":"call_1","output":"README.md"}],
             "tools":[{"type":"custom","name":"apply_patch","description":"Edits files.","format":{"type":"grammar","syntax":"lark","definition":"start: x"}}],
             "tool_choice":"auto","parallel_tool_calls":true,"reasoning":{"effort":"medium","summary":"auto"},
             "include":["reasoning.encrypted_content"],"prompt_cache_key":"t-1","client_metadata":{"session_id":"s-1"},
             "a_field_added_tomorrow":{"x":1.50}}
            """;

    static final String ANSWER = """
            {"id":"resp_9","object":"response","created_at":1760000000,"status":"completed","model":"gpt-5.5",
             "output":[{"type":"reasoning","id":"rs_2","summary":[],"encrypted_content":"gAAAAAB-next"},
                       {"type":"message","id":"msg_1","status":"completed","role":"assistant",
                        "content":[{"type":"output_text","text":"One file.","annotations":[]}]},
                       {"type":"custom_tool_call","id":"ctc_1","call_id":"call_2","name":"apply_patch","input":"*** Begin Patch","status":"completed"}],
             "usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":1024},
                      "output_tokens":80,"output_tokens_details":{"reasoning_tokens":64},"total_tokens":1280}}
            """;

    private MockRestServiceServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiProvider(builder.build());
    }

    /** A request carrying the body, with no text for governance to edit. */
    static ChatRequest request(String model) {
        ObjectNode raw = OpenAiResponsesBody.parse(BODY.getBytes(StandardCharsets.UTF_8));
        return ChatRequest.builder().model(model).messages(List.of())
                .tools(List.of(ToolDefinition.builder().name("apply_patch").custom(true).build()))
                .openAiResponses(new OpenAiResponsesBody(raw, List.of(), List.of())).build();
    }

    @Test
    void theBodyGoesToResponsesAsItCame_andTheAnswerIsReadAndKept() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.input[1].encrypted_content").value("gAAAAAB-opaque"))
                .andExpect(jsonPath("$.include[0]").value("reasoning.encrypted_content"))
                .andExpect(jsonPath("$.tools[0].type").value("custom"))
                .andExpect(jsonPath("$.tools[0].format.definition").value("start: x"))
                .andExpect(jsonPath("$.client_metadata.session_id").value("s-1"))
                .andExpect(jsonPath("$.reasoning.effort").value("medium"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .string(containsString("\"a_field_added_tomorrow\":{\"x\":1.50}")))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        ChatRequest request = request("gpt-5.5");

        ChatResponse response = provider.chat(request);

        server.verify();
        var message = response.getChoices().get(0).getMessage();
        assertThat(message.textContent()).isEqualTo("One file.");
        assertThat(message.getToolCalls()).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("apply_patch");
            assertThat(ToolDefinition.customInput(c.getArguments())).isEqualTo("*** Begin Patch");
        });
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(1200);
        assertThat(response.getUsage().getCachedInputTokens()).isEqualTo(1024);
        assertThat(response.getUsage().getReasoningTokens()).isEqualTo(64);
        assertThat(request.getOpenAiResponses().upstreamResponse().at("/output/0/encrypted_content").asText())
                .isEqualTo("gAAAAAB-next");
    }

    @Test
    void aStreamGoesToResponses_andEachEventRidesOnItsChunk() {
        String sse = """
                event: response.created
                data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_9","status":"in_progress","output":[]}}

                event: response.output_item.done
                data: {"type":"response.output_item.done","sequence_number":1,"output_index":0,"item":{"type":"reasoning","id":"rs_2","summary":[],"encrypted_content":"gAAAAAB-next"}}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","sequence_number":2,"item_id":"msg_1","output_index":1,"content_index":0,"delta":"One"}

                event: response.output_item.added
                data: {"type":"response.output_item.added","sequence_number":3,"output_index":2,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_2","name":"apply_patch","input":"","status":"in_progress"}}

                event: response.custom_tool_call_input.delta
                data: {"type":"response.custom_tool_call_input.delta","sequence_number":4,"item_id":"ctc_1","output_index":2,"delta":"say \\"hi\\""}

                event: response.output_item.done
                data: {"type":"response.output_item.done","sequence_number":5,"output_index":2,"item":{"type":"custom_tool_call","id":"ctc_1","call_id":"call_2","name":"apply_patch","input":"say \\"hi\\"","status":"completed"}}

                event: response.completed
                data: {"type":"response.completed","sequence_number":6,"response":{"id":"resp_9","status":"completed","usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":4},"output_tokens":5,"output_tokens_details":{"reasoning_tokens":3},"total_tokens":15}}}

                """;
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(jsonPath("$.stream").value(true))
                .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

        List<SseChunk> chunks = new ArrayList<>();
        provider.streamChat(request("gpt-5.5")).forEachRemaining(chunks::add);

        assertThat(chunks).hasSize(7).allSatisfy(c -> assertThat(c.getResponsesEvent()).isNotNull());
        assertThat(chunks.get(1).getResponsesEvent().data()).contains("gAAAAAB-next");
        assertThat(chunks.get(2).getDelta()).isEqualTo("One");
        StringBuilder args = new StringBuilder();
        chunks.stream().filter(c -> c.getToolCalls() != null).flatMap(c -> c.getToolCalls().stream())
                .forEach(f -> args.append(f.argumentsFragment() == null ? "" : f.argumentsFragment()));
        assertThat(ToolDefinition.customInput(args.toString())).isEqualTo("say \"hi\"");
        SseChunk last = chunks.get(6);
        assertThat(last.isDone()).isTrue();
        assertThat(last.getFinishReason()).isEqualTo("tool_calls");
        assertThat(last.getUsage().getCachedInputTokens()).isEqualTo(4);
        assertThat(last.getUsage().getReasoningTokens()).isEqualTo(3);
    }

    @Test
    void aStreamThatEndsBeforeItCompletes_isAProviderError() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withSuccess("""
                        data: {"type":"response.output_text.delta","sequence_number":0,"item_id":"m","output_index":0,"content_index":0,"delta":"Hal"}

                        """, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> it = provider.streamChat(request("gpt-5.5"));
        it.next();
        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class).hasMessageContaining("before the response completed");
    }

    @Test
    void aRefusalSaysWhatOpenAiObjectedTo() {
        server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"message":"Unsupported parameter: 'reasoning.effort' is not supported with this model.","type":"invalid_request_error","code":"unsupported_parameter"}}
                        """));

        assertThatThrownBy(() -> provider.chat(request("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("reasoning.effort");
    }

    @Test
    void withoutABody_itIsChatCompletionsAsBefore() {
        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"id":"c","object":"chat.completion","created":1,"model":"gpt-4o",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("gpt-4o")
                .messages(List.of(com.dvarahq.core.model.MultimodalMessage.user("hi"))).build());

        server.verify();
    }
}
