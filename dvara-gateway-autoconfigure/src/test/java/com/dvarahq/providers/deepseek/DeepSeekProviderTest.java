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
package com.dvarahq.providers.deepseek;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Checks what is specific to DeepSeek: the model prefix, the capability declaration and the
 * {@code "DeepSeek"} label on upstream errors. The shared chat, streaming and response-mapping
 * behaviour of {@link com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider} is tested in
 * {@link com.dvarahq.providers.qwen.QwenProviderTest} and {@code OpenAiProviderTest}.
 */
class DeepSeekProviderTest {

    private MockRestServiceServer server;
    private DeepSeekProvider      provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com/v1");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new DeepSeekProvider(builder.build());
    }

    @Test
    void supports_deepseekChat_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("deepseek-chat").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_deepseekReasoner_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("deepseek-reasoner").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_qwenModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("qwen2.5-72b").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void name_returnsDeepSeek() {
        assertThat(provider.name()).isEqualTo("deepseek");
    }

    @Test
    void capabilities_streamingToolsJsonMode_butNotStructuredOutputs() {
        ProviderCapabilities caps = provider.capabilities();
        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsToolCalls()).isTrue();
        assertThat(caps.supportsStreamingToolCalls()).isTrue();
        assertThat(caps.supportsJsonMode()).isTrue();
        assertThat(caps.supportsStructuredOutputs()).isFalse(); // not every DeepSeek model honours a schema, so it is declared off
        assertThat(caps.supportsVision()).isFalse();
        assertThat(caps.maxContextTokens()).isEqualTo(64_000);
    }

    @Test
    void chat_serverError_throwsGatewayException_withDeepSeekLabel() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("deepseek-chat")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("DeepSeek API error");

        server.verify();
    }

    @Test
    void listModels_returnsEmptyList_onUpstreamError() {
        server.expect(requestTo(containsString("/models")))
              .andRespond(withServerError());
        assertThat(provider.listModels()).isEmpty();
    }

    @Test
    void chat_keepsMaxTokens_whateverTheModelIsCalled() {
        // Only OpenAI and Azure OpenAI translate the token limit; another OpenAI-compatible server keeps
        // the field every such server knows.
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(jsonPath("$.max_tokens").value(50))
              .andExpect(jsonPath("$.max_completion_tokens").doesNotExist())
              .andRespond(withSuccess("""
                      {"id":"c1","object":"chat.completion","created":1,"model":"deepseek-reasoner",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("deepseek-reasoner")
                .messages(List.of(MultimodalMessage.user("hi"))).maxTokens(50).build());

        server.verify();
    }

    /** Another OpenAI-compatible server may refuse a field it does not know, so OpenAI's tuning fields are not sent. */
    @Test
    void openAisTuningFieldsAreNotSent() {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/chat/completions")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.reasoning_effort").doesNotExist())
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.verbosity").doesNotExist())
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.prompt_cache_key").doesNotExist())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"id":"c","object":"chat.completion","created":1,"model":"deepseek-chat",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("deepseek-chat")
                .messages(List.of(com.dvarahq.core.model.MultimodalMessage.user("hi")))
                .reasoningEffort("high").verbosity("low").promptCacheKey("thread-1").build());

        server.verify();
    }

    /** Without custom tools or strict, a custom tool goes as a function taking its input as one string. */
    @Test
    void aCustomToolGoesAsAFunction_andStrictIsNotSent() {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.containsString("/chat/completions")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.tools[0].type").value("function"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.tools[0].function.parameters.required[0]").value("input"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.tools[0].function.description").value(org.hamcrest.Matchers.containsString("lark grammar")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.tools[1].function.strict").doesNotExist())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"id":"c","object":"chat.completion","created":1,"model":"deepseek-chat",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("deepseek-chat")
                .messages(List.of(com.dvarahq.core.model.MultimodalMessage.user("hi")))
                .tools(List.of(
                        com.dvarahq.core.model.ToolDefinition.builder().name("apply_patch").description("Edits files.")
                                .custom(true).format(java.util.Map.of("type", "grammar", "syntax", "lark", "definition", "start: x"))
                                .build(),
                        com.dvarahq.core.model.ToolDefinition.builder().name("f")
                                .parameters(java.util.Map.of("type", "object")).strict(true).build()))
                .build());

        server.verify();
    }
}
