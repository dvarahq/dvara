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
package com.dvarahq.providers.anthropic;

import com.dvarahq.core.model.AnthropicMessagesBody;
import com.dvarahq.core.model.AnthropicPassthrough;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The opt-in prompt-cache marker on bodies the provider builds: same text, same order, one breakpoint. */
class AnthropicPromptCacheMarkerTest {

    private static final String OK = """
            {"id":"msg-1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
             "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":1,"output_tokens":1}}""";

    private MockRestServiceServer server;
    private AnthropicProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.anthropic.com");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AnthropicProvider(builder.build());
    }

    private static MultimodalMessage system(String text) {
        return MultimodalMessage.builder().role("system").content(List.of(new ContentBlock.TextBlock(text))).build();
    }

    private static List<ToolDefinition> tools() {
        return List.of(
                ToolDefinition.builder().name("read_file").description("Read a file")
                        .parameters(Map.of("type", "object")).build(),
                ToolDefinition.builder().name("write_file").description("Write a file")
                        .parameters(Map.of("type", "object")).build());
    }

    private ChatRequest.ChatRequestBuilder request(boolean withSystem, Boolean marker) {
        List<MultimodalMessage> messages = withSystem
                ? List.of(system("You are a careful coding agent."), MultimodalMessage.user("fix the bug"))
                : List.of(MultimodalMessage.user("fix the bug"));
        return ChatRequest.builder().model("claude-sonnet-4-5").messages(messages).tools(tools())
                .promptCacheMarker(marker);
    }

    private org.springframework.test.web.client.ResponseActions expectMessages() {
        return server.expect(requestTo(containsString("/v1/messages"))).andExpect(method(HttpMethod.POST));
    }

    @Test
    void withTheFlag_theSystemPromptBecomesOneMarkedBlock_withTheSameText() {
        expectMessages()
                .andExpect(jsonPath("$.system[0].type").value("text"))
                .andExpect(jsonPath("$.system[0].text").value("You are a careful coding agent."))
                .andExpect(jsonPath("$.system[0].cache_control.type").value("ephemeral"))
                .andExpect(jsonPath("$.system.length()").value(1))
                .andExpect(jsonPath("$.tools[0].name").value("read_file"))
                .andExpect(jsonPath("$.tools[1].name").value("write_file"))
                .andExpect(jsonPath("$.tools[0].cache_control").doesNotExist())
                .andExpect(jsonPath("$.tools[1].cache_control").doesNotExist())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request(true, true).build());

        server.verify();
    }

    @Test
    void withTheFlagAndNoSystemPrompt_theLastToolIsMarked() {
        expectMessages()
                .andExpect(jsonPath("$.system").doesNotExist())
                .andExpect(jsonPath("$.tools[0].cache_control").doesNotExist())
                .andExpect(jsonPath("$.tools[1].name").value("write_file"))
                .andExpect(jsonPath("$.tools[1].cache_control.type").value("ephemeral"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request(false, true).build());

        server.verify();
    }

    @Test
    void withoutTheFlag_theBodyIsAsBefore() {
        expectMessages()
                .andExpect(jsonPath("$.system").value("You are a careful coding agent."))
                .andExpect(jsonPath("$..cache_control").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request(true, null).build());

        server.verify();
    }

    @Test
    void theProvidersOwnSetting_marksEveryBodyItBuilds() {
        provider.setPromptCacheMarker(true);
        expectMessages()
                .andExpect(jsonPath("$.system[0].cache_control.type").value("ephemeral"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request(true, null).build());

        server.verify();
    }

    @Test
    void aCallersOwnMessagesBody_isNeverChanged() {
        provider.setPromptCacheMarker(true);
        AnthropicMessagesBody body = AnthropicMessagesBody.parse("""
                {"model":"claude-sonnet-4-5","max_tokens":1024,"system":"You are a careful coding agent.",
                 "messages":[{"role":"user","content":"fix the bug"}]}""".getBytes(StandardCharsets.UTF_8));
        expectMessages()
                .andExpect(jsonPath("$.system").value("You are a careful coding agent."))
                .andExpect(jsonPath("$..cache_control").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("claude-sonnet-4-5")
                .messages(List.of(system("You are a careful coding agent."), MultimodalMessage.user("fix the bug")))
                .anthropic(new AnthropicPassthrough(null, Map.of(), null, body)).promptCacheMarker(true).build());

        server.verify();
    }
}
