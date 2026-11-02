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
package com.dvarahq.providers.bedrock;

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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The opt-in prompt-cache marker on Converse bodies: one cachePoint after the stable prefix. */
class BedrockPromptCacheMarkerTest {

    private static final String OK = """
            {"output":{"message":{"role":"assistant","content":[{"text":"hi"}]}},"stopReason":"end_turn",
             "usage":{"inputTokens":1,"outputTokens":1,"totalTokens":2}}""";

    private MockRestServiceServer server;
    private BedrockProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://bedrock-runtime.us-east-1.amazonaws.com");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new BedrockProvider(builder.build());
    }

    private ChatRequest request(boolean withSystem, Boolean marker) {
        List<MultimodalMessage> messages = withSystem
                ? List.of(MultimodalMessage.builder().role("system")
                        .content(List.of(new ContentBlock.TextBlock("You are a careful coding agent."))).build(),
                        MultimodalMessage.user("fix the bug"))
                : List.of(MultimodalMessage.user("fix the bug"));
        return ChatRequest.builder().model("bedrock/anthropic.claude-sonnet-4-5").messages(messages)
                .tools(List.of(ToolDefinition.builder().name("read_file").description("Read a file")
                        .parameters(Map.of("type", "object")).build()))
                .promptCacheMarker(marker).build();
    }

    private org.springframework.test.web.client.ResponseActions expectConverse() {
        return server.expect(requestTo(containsString("/converse"))).andExpect(method(HttpMethod.POST));
    }

    @Test
    void withTheFlag_aCachePointFollowsTheSystemBlocks() {
        expectConverse()
                .andExpect(jsonPath("$.system[0].text").value("You are a careful coding agent."))
                .andExpect(jsonPath("$.system[1].cachePoint.type").value("default"))
                .andExpect(jsonPath("$.system.length()").value(2))
                .andExpect(jsonPath("$.toolConfig.tools.length()").value(1))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request(true, true));

        server.verify();
    }

    @Test
    void withTheFlagAndNoSystemPrompt_aCachePointFollowsTheTools() {
        expectConverse()
                .andExpect(jsonPath("$.system").doesNotExist())
                .andExpect(jsonPath("$.toolConfig.tools[0].toolSpec.name").value("read_file"))
                .andExpect(jsonPath("$.toolConfig.tools[1].cachePoint.type").value("default"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request(false, true));

        server.verify();
    }

    @Test
    void withoutTheFlag_noCachePoint_unlessTheProviderSettingIsOn() {
        expectConverse()
                .andExpect(jsonPath("$..cachePoint").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        provider.chat(request(true, null));
        server.verify();

        server.reset();
        provider.setPromptCacheMarker(true);
        expectConverse()
                .andExpect(jsonPath("$.system[1].cachePoint.type").value("default"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        provider.chat(request(true, null));
        server.verify();
    }
}
