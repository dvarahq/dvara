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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** DeepSeek reports its cache hits as {@code prompt_cache_hit_tokens}, a part of {@code prompt_tokens}. */
class DeepSeekCacheUsageTest {

    @Test
    @DisplayName("DeepSeek: prompt_cache_hit_tokens is the cached part of the input")
    void deepSeekCacheHit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.deepseek.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/chat/completions")))
                .andRespond(withSuccess("""
                        {"id":"c1","model":"deepseek-reasoner","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],"usage":{"prompt_tokens":300,"completion_tokens":90,"total_tokens":390,
                         "prompt_cache_hit_tokens":256,"prompt_cache_miss_tokens":44,
                         "completion_tokens_details":{"reasoning_tokens":70}}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse.Usage u = new DeepSeekProvider(builder.build()).chat(ChatRequest.builder()
                .model("deepseek-reasoner").messages(List.of(MultimodalMessage.user("hi"))).build()).getUsage();

        assertThat(u.getCachedInputTokens()).isEqualTo(256);
        assertThat(u.getReasoningTokens()).isEqualTo(70);
        assertThat(u.getPromptTokens()).isGreaterThanOrEqualTo(u.getCachedInputTokens());
        assertThat(u.getCompletionTokens()).isGreaterThanOrEqualTo(u.getReasoningTokens());
        assertThat(u.getTotalTokens()).isEqualTo(390);
    }
}
