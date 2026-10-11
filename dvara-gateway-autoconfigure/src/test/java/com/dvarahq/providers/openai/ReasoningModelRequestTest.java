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
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OpenAI's reasoning models refuse {@code max_tokens}; the token limit reaches them as
 * {@code max_completion_tokens}, and when OpenAI refuses a request the caller learns why.
 */
class ReasoningModelRequestTest {

    private static final String OK = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-5-mini",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hi there friend"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    /** What OpenAI answers when a GPT-5 model is sent max_tokens. */
    private static final String MAX_TOKENS_REFUSED = """
            {"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.",
             "type":"invalid_request_error","param":"max_tokens","code":"unsupported_parameter"}}
            """;

    private MockRestServiceServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiProvider(builder.build());
    }

    private static ChatRequest request(String model, Integer maxTokens) {
        return ChatRequest.builder().model(model)
                .messages(List.of(MultimodalMessage.user("Say hi in 3 words."))).maxTokens(maxTokens).build();
    }

    @Test
    void gpt5_isSentMaxCompletionTokens_andNoMaxTokens() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.max_completion_tokens").value(4096))
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request("gpt-5.4-mini", 4096));

        server.verify();
    }

    @Test
    void oSeries_isSentMaxCompletionTokens() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.max_completion_tokens").value(300))
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request("o3-mini", 300));

        server.verify();
    }

    @Test
    void gpt4o_keepsMaxTokens() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.max_tokens").value(300))
                .andExpect(jsonPath("$.max_completion_tokens").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(request("gpt-4o", 300));

        server.verify();
    }

    @Test
    void aStreamToGpt5_isSentMaxCompletionTokens() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.max_completion_tokens").value(64))
                .andExpect(jsonPath("$.max_tokens").doesNotExist())
                .andExpect(jsonPath("$.stream").value(true))
                .andRespond(withSuccess("""
                        data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5-mini","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """, MediaType.TEXT_EVENT_STREAM));

        provider.streamChat(request("gpt-5-mini", 64).toBuilder().stream(true).build()).hasNext();

        server.verify();
    }

    @Test
    void aRefusedRequest_carriesOpenAisOwnReason() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(MAX_TOKENS_REFUSED));

        assertThatThrownBy(() -> provider.chat(request("gpt-4o", 10)))
                .isInstanceOf(GatewayException.class)
                .hasMessage("OpenAI API error 400: unsupported_parameter: Unsupported parameter: 'max_tokens' is not"
                        + " supported with this model. Use 'max_completion_tokens' instead.")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_REJECTED_REQUEST"));
    }

    @Test
    void aRefusedStream_carriesOpenAisOwnReason() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(MAX_TOKENS_REFUSED));

        assertThatThrownBy(() -> provider.streamChat(request("gpt-4o", 10).toBuilder().stream(true).build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageStartingWith("OpenAI streaming error 400: unsupported_parameter: Unsupported parameter:")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_REJECTED_REQUEST"));
    }

    @Test
    void anAuthenticationFailure_keepsTheGatewaysOwnMessage() {
        // A 401 is about the gateway's own key, and OpenAI's message quotes part of it: none of it is returned.
        server.expect(requestTo(containsString("/chat/completions")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"message":"Incorrect API key provided: sk-proj-abcd****wxyz.","code":"invalid_api_key"}}
                        """));

        assertThatThrownBy(() -> provider.chat(request("gpt-5-mini", 10)))
                .isInstanceOf(GatewayException.class)
                .hasMessage("OpenAI API error 401 (invalid API key - check your provider credentials)");
    }
}
