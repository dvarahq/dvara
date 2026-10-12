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
package com.dvarahq.server.providers;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Provider answers read as the running application reads them: by a RestClient whose JSON converter is the
 * application's own (Jackson 3). The provider modules' own tests run with the older converter, which reads types
 * this one cannot, so a response type that only one of them reads fails here first.
 */
class ProviderResponsesReadByTheApplicationsJsonTest {

    private static RestClient.Builder applicationsRestClient() {
        // The default converters, as the application's own: with its JSON library on the classpath, that library
        // reads the answers.
        return RestClient.builder();
    }

    @Test
    void aWholeClaudeAnswerWithThinking_isRead() {
        RestClient.Builder builder = applicationsRestClient();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/v1/messages")))
                .andRespond(withSuccess("""
                        {"id":"msg-u","type":"message","role":"assistant","model":"claude-sonnet-4-5","stop_reason":"end_turn",
                         "content":[{"type":"thinking","thinking":"Let me see.","signature":"sig"},{"type":"text","text":"42"}],
                         "usage":{"input_tokens":10,"output_tokens":300,"output_tokens_details":{"thinking_tokens":280}}}
                        """, MediaType.APPLICATION_JSON));
        AnthropicProvider provider = new AnthropicProvider(key -> Optional.of("test-key"), builder);

        ChatResponse response = provider.chat(ChatRequest.builder().model("claude-sonnet-4-5")
                .messages(List.of(MultimodalMessage.user("What is six times seven?"))).reasoningEffort("low").build());

        assertThat(response.getChoices().get(0).getMessage().textContent()).contains("42");
        assertThat(response.getUsage().getReasoningTokens()).isEqualTo(280);
    }

    @Test
    void aWholeOpenAiAnswer_keepsItsUsageBlockAsSent() {
        RestClient.Builder builder = applicationsRestClient();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/chat/completions")))
                .andRespond(withSuccess("""
                        {"id":"c","object":"chat.completion","created":1,"model":"gpt-5.4-mini",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,
                                  "prompt_tokens_details":{"cached_tokens":4,"cache_write_tokens":3},
                                  "completion_tokens_details":{"reasoning_tokens":0}}}
                        """, MediaType.APPLICATION_JSON));
        OpenAiProvider provider = new OpenAiProvider(key -> Optional.of("test-key"), "https://api.openai.com/v1", builder);

        ChatResponse response = provider.chat(ChatRequest.builder().model("gpt-5.4-mini")
                .messages(List.of(MultimodalMessage.user("hi"))).build());

        assertThat(response.getUsage().getCachedInputTokens()).isEqualTo(4);
        assertThat(response.getUsage().getCacheWriteTokens()).isEqualTo(3);
        assertThat(response.getUsage().getAsSent()).containsKey("completion_tokens_details");
    }
}
