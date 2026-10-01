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
package com.dvarahq.core.model;

import com.dvarahq.core.exception.GatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicMessagesBodyTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String BODY = """
            {"model": "claude-x", "max_tokens": 100, "temperature": 0.70, "future_field": {"a": [1, 2.50]},
             "system": [{"type": "text", "text": "Top system.", "cache_control": {"type": "ephemeral"}}],
             "messages": [
               {"role": "user", "content": [{"type": "text", "text": "Hello"}, {"type": "future_block", "x": 1}]},
               {"role": "system", "content": "Mid system, alice@example.com."},
               {"role": "assistant", "content": [
                 {"type": "thinking", "thinking": "hm", "signature": "sig"},
                 {"type": "tool_use", "id": "t1", "name": "Read", "input": {"path": "a.txt"}},
                 {"type": "text", "text": "Reading."}]},
               {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "t1", "content": "line one"},
                                            {"type": "text", "text": "Thanks"}]}]}
            """;

    static AnthropicMessagesBody body() {
        return AnthropicMessagesBody.parse(BODY.getBytes(StandardCharsets.UTF_8));
    }

    static ChatRequest request(AnthropicMessagesBody body, List<MultimodalMessage> messages) {
        return ChatRequest.builder().model("claude-x").maxTokens(100).temperature(0.7).messages(messages).build();
    }

    @Test
    void theViewReadsEveryPieceOfTextInOrder_withTheSystemMessageAtItsPlace() {
        List<MultimodalMessage> view = body().messages();

        assertThat(view).extracting(MultimodalMessage::getRole)
                .containsExactly("system", "user", "system", "assistant", "tool", "user");
        assertThat(view.get(2).getContent()).containsExactly(new ContentBlock.TextBlock("Mid system, alice@example.com."));
        assertThat(view.get(3).getContent()).containsExactly(
                new ContentBlock.ThinkingBlock("hm", "sig"), new ContentBlock.TextBlock("Reading."));
        assertThat(view.get(3).getToolCalls().get(0).getArguments()).isEqualTo("{\"path\":\"a.txt\"}");
        assertThat(view.get(4).getToolCallId()).isEqualTo("t1");
    }

    @Test
    void anUngovernedRequestIsSentAsItCame() throws Exception {
        AnthropicMessagesBody body = body();

        String sent = body.governedJson(request(body, body.messages()));

        assertThat(JSON.readTree(sent)).isEqualTo(JSON.readTree(BODY));
        assertThat(sent).as("numbers exactly as they came").contains("0.70").contains("2.50");
    }

    @Test
    void governedTextIsWrittenBackWhereItCameFrom_andNothingElseChanges() throws Exception {
        AnthropicMessagesBody body = body();
        List<MultimodalMessage> governed = new ArrayList<>(body.messages());
        governed.set(2, governed.get(2).toBuilder()
                .content(List.of(new ContentBlock.TextBlock("Mid system, [REDACTED_EMAIL]."))).build());
        MultimodalMessage assistant = governed.get(3);
        governed.set(3, assistant.toBuilder().toolCalls(List.of(ToolCall.builder().id("t1").name("Read")
                .arguments("{\"path\":\"[REDACTED]\"}").build())).build());

        JsonNode sent = JSON.readTree(body.governedJson(request(body, governed).toBuilder().model("claude-routed").build()));

        ObjectNode expected = (ObjectNode) JSON.readTree(BODY);
        ((ObjectNode) expected.path("messages").get(1)).put("content", "Mid system, [REDACTED_EMAIL].");
        ((ObjectNode) expected.path("messages").get(2).path("content").get(1)).set("input",
                JSON.readTree("{\"path\":\"[REDACTED]\"}"));
        expected.put("model", "claude-routed");
        assertThat(sent).isEqualTo(expected);
    }

    @Test
    void aBlockTypeTheGatewayDoesNotReadIsOpaque_countedAndLeftOutForOthers() {
        AnthropicMessagesBody body = body();

        assertThat(body.opaqueBlocks()).containsExactly(Map.entry("future_block", 1));
        assertThat(body.ignoredByOthers()).containsExactlyInAnyOrder("block:future_block", "cache_control");
    }

    @Test
    void aGovernanceStepThatChangesTheConversationsShapeIsRefused() {
        AnthropicMessagesBody body = body();
        List<MultimodalMessage> shorter = new ArrayList<>(body.messages());
        shorter.remove(1);

        assertThatThrownBy(() -> body.governedJson(request(body, shorter)))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY");
    }

    @Test
    void aResponseIsReadBlockForBlock_andGovernedTextIsWrittenBackInPlace() throws Exception {
        ObjectNode response = AnthropicMessagesBody.parseResponse("""
                {"id": "m", "content": [{"type": "thinking", "thinking": "t", "signature": "s"},
                  {"type": "text", "text": "Mail bob@example.com"}, {"type": "future_block", "y": 2},
                  {"type": "text", "text": "Bye"}], "usage": {"input_tokens": 1}}
                """);
        Map<String, Integer> opaque = new LinkedHashMap<>();

        MultimodalMessage read = AnthropicMessagesBody.responseMessage(response, opaque);
        MultimodalMessage governed = read.toBuilder().content(List.of(read.getContent().get(0),
                new ContentBlock.TextBlock("Mail [REDACTED_EMAIL]"), read.getContent().get(2))).build();
        JsonNode out = AnthropicMessagesBody.governedResponse(response, governed);

        assertThat(opaque).containsExactly(Map.entry("future_block", 1));
        assertThat(out.path("content").get(1).path("text").asText()).isEqualTo("Mail [REDACTED_EMAIL]");
        assertThat(out.path("content").get(2)).isEqualTo(response.path("content").get(2));
        assertThat(AnthropicMessagesBody.governedResponse(response,
                read.toBuilder().content(List.of(new ContentBlock.TextBlock("joined"))).build()))
                .as("no longer block for block").isNull();
    }
}
