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

class GeminiGenerateContentBodyTest {

    /**
     * A turn of a Gemini CLI session in the shape the Google GenAI SDK sends it: a system instruction, a
     * thought with its signature, a function call, the function's result, function declarations as JSON
     * Schema, and generation settings with thinking turned on.
     */
    static final String CLI_TURN = """
            {"contents": [
               {"role": "user", "parts": [{"text": "List the files in the repo"}]},
               {"role": "model", "parts": [
                  {"text": "Listing the directory.", "thought": true, "thoughtSignature": "c2lnLTE="},
                  {"functionCall": {"name": "list_directory", "args": {"dir_path": "/repo", "depth": 2.50,
                                    "ignore": ["*.log"]}}, "thoughtSignature": "c2lnLTI="}]},
               {"role": "user", "parts": [
                  {"functionResponse": {"name": "list_directory", "response": {"output": "README.md\\nsrc/"}}}]}],
             "systemInstruction": {"role": "user", "parts": [{"text": "You are an interactive CLI agent."}]},
             "tools": [{"functionDeclarations": [{"name": "list_directory", "description": "Lists a directory.",
                 "parametersJsonSchema": {"type": "object", "properties": {"dir_path": {"type": "string"}},
                                          "required": ["dir_path"]}}]}],
             "toolConfig": {"functionCallingConfig": {"mode": "AUTO"}},
             "generationConfig": {"temperature": 1, "topP": 0.95, "topK": 64,
                                  "thinkingConfig": {"includeThoughts": true, "thinkingBudget": 8192}}}
            """;

    private static GeminiGenerateContentBody parse(String json) {
        return GeminiGenerateContentBody.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theConversationIsReadAsTheGatewaysMessages() {
        GeminiGenerateContentBody body = parse(CLI_TURN);

        List<MultimodalMessage> view = body.messages();
        assertThat(view).extracting(MultimodalMessage::getRole)
                .containsExactly("system", "user", "assistant", "tool");
        assertThat(view.get(0).textContent()).isEqualTo("You are an interactive CLI agent.");
        ToolCall call = view.get(2).getToolCalls().get(0);
        assertThat(call.getName()).isEqualTo("list_directory");
        assertThat(view.get(2).getContent()).as("a thought is not answer text").isEmpty();
        // The result answers the call it follows, by the id the call was given.
        assertThat(view.get(3).getToolCallId()).isEqualTo(call.getId());
        assertThat(view.get(3).textContent()).isEqualTo("{\"output\":\"README.md\\nsrc/\"}");
        assertThat(body.opaqueParts()).containsEntry("thought", 1);
        assertThat(body.ignoredByOthers()).contains("part:thought", "generationConfig.topK",
                "generationConfig.thinkingConfig");
        assertThat(body.googleTools()).isEmpty();
    }

    @Test
    void anUngovernedBodyGoesOnByteForByteInEverythingThatMatters() throws Exception {
        GeminiGenerateContentBody body = parse(CLI_TURN);
        ChatRequest governed = ChatRequest.builder().model("gemini-3.8-flash").messages(body.messages())
                .temperature(1.0).topP(0.95).build();

        JsonNode sent = new ObjectMapper().readTree(body.governedJson(governed));

        JsonNode original = new ObjectMapper().readTree(CLI_TURN);
        assertThat(sent).isEqualTo(original);
        // The call's arguments, the thought and both signatures survive exactly, numbers included.
        assertThat(body.governedJson(governed)).contains("\"depth\":2.50", "c2lnLTE=", "c2lnLTI=");
    }

    @Test
    void governedTextIsWrittenBackWhereItCameFrom() throws Exception {
        GeminiGenerateContentBody body = parse(CLI_TURN);
        List<MultimodalMessage> edited = new ArrayList<>(body.messages());
        edited.set(0, edited.get(0).toBuilder()
                .content(List.of(new ContentBlock.TextBlock("You are an interactive CLI agent. [REDACTED]"))).build());
        edited.set(3, edited.get(3).toBuilder()
                .content(List.of(new ContentBlock.TextBlock("{\"output\":\"[REDACTED]\"}"))).build());

        JsonNode sent = new ObjectMapper().readTree(body.governedJson(
                ChatRequest.builder().model("gemini-3.8-flash").messages(edited).maxTokens(512).build()));

        assertThat(sent.at("/systemInstruction/parts/0/text").asText())
                .isEqualTo("You are an interactive CLI agent. [REDACTED]");
        assertThat(sent.at("/contents/2/parts/0/functionResponse/response/output").asText()).isEqualTo("[REDACTED]");
        assertThat(sent.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(512);
        assertThat(sent.at("/generationConfig/thinkingConfig/thinkingBudget").asInt()).isEqualTo(8192);
        assertThat(sent.at("/contents/1/parts/0/thoughtSignature").asText()).isEqualTo("c2lnLTE=");
    }

    @Test
    void aControlThatChangesTheConversationsShapeIsRefusedRatherThanSentHalfGoverned() {
        GeminiGenerateContentBody body = parse(CLI_TURN);
        List<MultimodalMessage> shorter = body.messages().subList(0, 2);

        assertThatThrownBy(() -> body.governedJson(ChatRequest.builder().model("m").messages(shorter).build()))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"));
    }

    @Test
    void theBuiltInToolsGoogleRunsAreNamed() {
        GeminiGenerateContentBody body = parse("""
                {"contents": [{"role": "user", "parts": [{"text": "news today"}]}],
                 "tools": [{"googleSearch": {}}, {"urlContext": {}},
                           {"functionDeclarations": [{"name": "read_file"}]}]}
                """);

        assertThat(body.googleTools()).containsExactly("googleSearch", "urlContext");
    }

    @Test
    void thePartsTheGatewayDoesNotReadAreKeptInOrder() {
        ObjectNode response = GeminiGenerateContentBody.parseResponse("""
                {"candidates": [{"content": {"role": "model", "parts": [
                    {"text": "Running it."},
                    {"executableCode": {"language": "PYTHON", "code": "print(1)"}},
                    {"codeExecutionResult": {"outcome": "OUTCOME_OK", "output": "1"}},
                    {"functionCall": {"name": "f", "args": {}}}]}}]}
                """);

        JsonNode unread = GeminiGenerateContentBody.unreadParts(response);

        assertThat(unread).hasSize(2);
        assertThat(unread.get(0).has("executableCode")).isTrue();
        assertThat(unread.get(1).path("codeExecutionResult").path("output").asText()).isEqualTo("1");
    }

    @Test
    void severalCallsToOneFunctionAreAnsweredInOrderWhenNoIdsAreSent() {
        GeminiGenerateContentBody body = parse("""
                {"contents": [
                   {"role": "user", "parts": [{"text": "read both"}]},
                   {"role": "model", "parts": [{"functionCall": {"name": "read_file", "args": {"p": "a"}}},
                                               {"functionCall": {"name": "read_file", "args": {"p": "b"}}}]},
                   {"role": "user", "parts": [
                      {"functionResponse": {"name": "read_file", "response": {"output": "A"}}},
                      {"functionResponse": {"name": "read_file", "response": {"output": "B"}}}]}]}
                """);

        List<MultimodalMessage> view = body.messages();
        List<ToolCall> calls = view.get(1).getToolCalls();
        assertThat(calls.get(0).getId()).isNotEqualTo(calls.get(1).getId());
        assertThat(view.get(2).getToolCallId()).isEqualTo(calls.get(0).getId());
        assertThat(view.get(3).getToolCallId()).isEqualTo(calls.get(1).getId());
    }

    @Test
    void theResponseIsReadPartForPart_andGovernedTextIsWrittenBackKeepingSignatures() {
        ObjectNode response = GeminiGenerateContentBody.parseResponse("""
                {"candidates": [{"content": {"role": "model", "parts": [
                    {"text": "Thinking about it.", "thought": true, "thoughtSignature": "c2ln"},
                    {"text": "Call me at 555-0100."},
                    {"functionCall": {"name": "run_shell_command", "args": {"command": "ls"}}, "thoughtSignature": "c2lnMg=="}]},
                  "finishReason": "STOP", "index": 0}],
                 "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 5, "thoughtsTokenCount": 3,
                                   "totalTokenCount": 18},
                 "modelVersion": "gemini-3.8-flash", "responseId": "r-1"}
                """);
        Map<String, Integer> opaque = new LinkedHashMap<>();

        MultimodalMessage read = GeminiGenerateContentBody.responseMessage(response, opaque);
        assertThat(read.getContent()).containsExactly(new ContentBlock.ThinkingBlock("Thinking about it.", "c2ln"),
                new ContentBlock.TextBlock("Call me at 555-0100."));
        assertThat(read.getToolCalls()).extracting(ToolCall::getArguments).containsExactly("{\"command\":\"ls\"}");

        MultimodalMessage governed = read.toBuilder().content(List.of(
                new ContentBlock.ThinkingBlock("Thinking about it.", "c2ln"),
                new ContentBlock.TextBlock("Call me at [PHONE]."))).build();
        ObjectNode out = GeminiGenerateContentBody.governedResponse(response, governed);

        assertThat(out.at("/candidates/0/content/parts/1/text").asText()).isEqualTo("Call me at [PHONE].");
        assertThat(out.at("/candidates/0/content/parts/2/thoughtSignature").asText()).isEqualTo("c2lnMg==");
        assertThat(out.at("/usageMetadata/thoughtsTokenCount").asInt()).isEqualTo(3);
        assertThat(out.at("/responseId").asText()).isEqualTo("r-1");
    }

    @Test
    void aBodyThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> parse("[]")).isInstanceOfSatisfying(GatewayException.class,
                e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
    }
}
