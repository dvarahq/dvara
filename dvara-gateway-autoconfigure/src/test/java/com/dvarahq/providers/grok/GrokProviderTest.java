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
package com.dvarahq.providers.grok;

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
 * Checks what is specific to Grok: its name, the {@code grok} model prefix and the provider-level
 * vision declaration. The shared chat and streaming behaviour is tested in
 * {@code QwenProviderTest} and {@code OpenAiProviderTest}.
 */
class GrokProviderTest {

    private MockRestServiceServer server;
    private GrokProvider          provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.x.ai/v1");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new GrokProvider(builder.build());
    }

    @Test
    void supports_grok2_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("grok-2-1212").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_grokVision_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("grok-2-vision-1212").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_grok3_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("grok-3-latest").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_gptModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void name_returnsGrok() {
        assertThat(provider.name()).isEqualTo("grok");
    }

    @Test
    void capabilities_overDeclareVisionAndStructuredOutputs_atProviderLevel() {
        // Vision is declared on at the provider level even though only the vision models take
        // images, so capability-aware routing keeps Grok in the candidate pool for a vision
        // request; a non-vision Grok model given an image answers with a provider error.
        ProviderCapabilities caps = provider.capabilities();
        assertThat(caps.supportsVision()).isTrue();
        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsToolCalls()).isTrue();
        assertThat(caps.supportsStreamingToolCalls()).isTrue();
        assertThat(caps.supportsStructuredOutputs()).isTrue();
        assertThat(caps.supportsJsonMode()).isTrue();
        assertThat(caps.maxContextTokens()).isEqualTo(131_072);
    }

    @Test
    void chat_serverError_throwsGatewayException_withGrokLabel() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("grok-2-1212")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Grok API error");

        server.verify();
    }

    @Test
    void listModels_returnsEmptyList_onUpstreamError() {
        server.expect(requestTo(containsString("/models")))
              .andRespond(withServerError());
        assertThat(provider.listModels()).isEmpty();
    }
}