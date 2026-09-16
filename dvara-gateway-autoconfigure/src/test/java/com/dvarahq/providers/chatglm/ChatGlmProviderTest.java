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
package com.dvarahq.providers.chatglm;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * Checks what is specific to ChatGLM: the {@code glm} model prefix (not {@code chatglm}), the
 * provider-level capability declaration and the label on upstream errors. The shared chat and
 * streaming behaviour is tested in {@code QwenProviderTest} and {@code OpenAiProviderTest}.
 */
class ChatGlmProviderTest {

    private MockRestServiceServer server;
    private ChatGlmProvider       provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://open.bigmodel.cn/api/paas/v4");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new ChatGlmProvider(builder.build());
    }

    @Test
    void supports_glm4_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("glm-4").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_glm4Vision_returnsTrue() {
        // The provider claims glm-4v models. capabilities() declares vision off at the provider
        // level, so capability-aware routing sends vision requests elsewhere, while a route that
        // names glm-4v directly still gets through.
        ChatRequest request = ChatRequest.builder().model("glm-4v-plus").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_chatglmBare_returnsFalse() {
        // The brand is ChatGLM, but the API's model names start with "glm-", so "chatglm" is
        // not a recognised prefix.
        ChatRequest request = ChatRequest.builder().model("chatglm-pro").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supports_gptModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void name_returnsChatglm() {
        assertThat(provider.name()).isEqualTo("chatglm");
    }

    @Test
    void capabilities_visionOff_atProviderLevel() {
        // Only the glm-4v models take images, so vision is declared off at the provider level.
        // Capability-aware routing then leaves ChatGLM out of the candidate pool for a vision
        // request; an operator who wants it routes to a vision-capable provider explicitly.
        ProviderCapabilities caps = provider.capabilities();
        assertThat(caps.supportsVision()).isFalse();
        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsToolCalls()).isTrue();
        assertThat(caps.supportsStreamingToolCalls()).isTrue();
        assertThat(caps.supportsJsonMode()).isTrue();
        assertThat(caps.maxContextTokens()).isEqualTo(128_000);
    }

    @Test
    void chat_serverError_throwsGatewayException_withChatGlmLabel() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("glm-4")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("ChatGLM API error");

        server.verify();
    }

    @Test
    void listModels_returnsEmptyList_onUpstreamError() {
        server.expect(requestTo(containsString("/models")))
              .andRespond(withServerError());
        assertThat(provider.listModels()).isEmpty();
    }
}