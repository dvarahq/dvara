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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** OpenAI is sent the reasoning effort, verbosity and prompt cache key a request carries. */
class OpenAiTuningRequestTest {

    private static final String OK = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-5.5",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}
            """;

    private MockRestServiceServer server;
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiProvider(builder.build());
    }

    @Test
    void theTuningFieldsAreSent() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.reasoning_effort").value("high"))
                .andExpect(jsonPath("$.verbosity").value("low"))
                .andExpect(jsonPath("$.prompt_cache_key").value("thread-1"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("gpt-5.5").messages(List.of(MultimodalMessage.user("hi")))
                .reasoningEffort("high").verbosity("low").promptCacheKey("thread-1").build());

        server.verify();
    }

    @Test
    void noneIsSentWhenTheRequestSetsNone() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andExpect(jsonPath("$.verbosity").doesNotExist())
                .andExpect(jsonPath("$.prompt_cache_key").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("gpt-5.5").messages(List.of(MultimodalMessage.user("hi"))).build());

        server.verify();
    }

    private static final com.dvarahq.core.model.ToolDefinition PATCH = com.dvarahq.core.model.ToolDefinition.builder()
            .name("apply_patch").description("Edits files.").custom(true)
            .format(java.util.Map.of("type", "grammar", "syntax", "lark", "definition", "start: \"x\""))
            .build();

    @Test
    void strictIsSentOnAFunctionTool() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.tools[0].function.strict").value(true))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder().model("gpt-5.5").messages(List.of(MultimodalMessage.user("hi")))
                .tools(List.of(com.dvarahq.core.model.ToolDefinition.builder().name("f")
                        .parameters(java.util.Map.of("type", "object")).strict(true).build()))
                .build());

        server.verify();
    }

    @Test
    void aCustomToolAndAnEarlierCallToIt_goAsCustom() {
        server.expect(requestTo(containsString("/chat/completions")))
                .andExpect(jsonPath("$.tools[0].type").value("custom"))
                .andExpect(jsonPath("$.tools[0].custom.name").value("apply_patch"))
                .andExpect(jsonPath("$.tools[0].custom.format.type").value("grammar"))
                .andExpect(jsonPath("$.tools[0].custom.format.grammar.syntax").value("lark"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].type").value("custom"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].custom.input").value("*** Begin Patch"))
                .andRespond(withSuccess("""
                        {"id":"c","object":"chat.completion","created":1,"model":"gpt-5.5",
                         "choices":[{"index":0,"message":{"role":"assistant","content":null,
                           "tool_calls":[{"id":"call_2","type":"custom","custom":{"name":"apply_patch","input":"*** End Patch"}}]},
                           "finish_reason":"tool_calls"}]}
                        """, MediaType.APPLICATION_JSON));

        MultimodalMessage earlier = MultimodalMessage.builder().role("assistant").content(List.of())
                .toolCalls(List.of(new com.dvarahq.core.model.ToolCall("call_1", "apply_patch",
                        com.dvarahq.core.model.ToolDefinition.customArguments("*** Begin Patch"))))
                .build();
        var response = provider.chat(ChatRequest.builder().model("gpt-5.5")
                .messages(List.of(MultimodalMessage.user("fix it"), earlier, MultimodalMessage.toolResult("call_1", "Done")))
                .tools(List.of(PATCH)).build());

        server.verify();
        var call = response.getChoices().get(0).getMessage().getToolCalls().get(0);
        org.assertj.core.api.Assertions.assertThat(call.getName()).isEqualTo("apply_patch");
        org.assertj.core.api.Assertions.assertThat(com.dvarahq.core.model.ToolDefinition.customInput(call.getArguments()))
                .isEqualTo("*** End Patch");
    }
}
