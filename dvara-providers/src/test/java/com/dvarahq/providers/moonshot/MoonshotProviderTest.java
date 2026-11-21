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
package com.dvarahq.providers.moonshot;

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
 * Checks what is specific to Moonshot: its name, the {@code moonshot} model prefix and the 200K
 * context window. The shared chat and streaming behaviour is tested in {@code QwenProviderTest}
 * and {@code OpenAiProviderTest}.
 */
class MoonshotProviderTest {

    private MockRestServiceServer server;
    private MoonshotProvider      provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.moonshot.cn/v1");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new MoonshotProvider(builder.build());
    }

    @Test
    void supports_moonshotV1_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("moonshot-v1-128k").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_kimiBare_returnsFalse() {
        // The product brand is "Kimi", but the API's model names start with "moonshot-", so
        // "kimi" is not a recognised prefix.
        ChatRequest request = ChatRequest.builder().model("kimi-chat").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supports_gptModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void name_returnsMoonshot() {
        assertThat(provider.name()).isEqualTo("moonshot");
    }

    @Test
    void capabilities_long200kContext_streamingToolsJsonMode() {
        // The 200K context window is what sets Moonshot apart.
        ProviderCapabilities caps = provider.capabilities();
        assertThat(caps.maxContextTokens()).isEqualTo(200_000);
        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsToolCalls()).isTrue();
        assertThat(caps.supportsStreamingToolCalls()).isTrue();
        assertThat(caps.supportsJsonMode()).isTrue();
        assertThat(caps.supportsStructuredOutputs()).isFalse();
        assertThat(caps.supportsVision()).isFalse();
    }

    @Test
    void chat_serverError_throwsGatewayException_withMoonshotLabel() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("moonshot-v1-128k")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Moonshot API error");

        server.verify();
    }

    @Test
    void listModels_returnsEmptyList_onUpstreamError() {
        server.expect(requestTo(containsString("/models")))
              .andRespond(withServerError());
        assertThat(provider.listModels()).isEmpty();
    }
}