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
package com.dvarahq.server.service;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.provider.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The request each provider is given: custom tools as functions, and images out of tool results, where it needs that. */
class ProviderShapesTest {

    private static final ToolDefinition PATCH = ToolDefinition.builder().name("apply_patch").description("Edits files.")
            .custom(true).format(Map.of("type", "grammar", "syntax", "lark", "definition", "start: x")).build();

    private static ChatRequest withPatch() {
        return ChatRequest.builder().model("m").messages(List.of(MultimodalMessage.user("hi"))).tools(List.of(PATCH)).build();
    }

    @Test
    void aCustomTool_isAFunctionForAProviderWithoutCustomTools() {
        ChatRequest shaped = ProviderShapes.forProvider(mock(LlmProvider.class), withPatch());

        ToolDefinition tool = shaped.getTools().get(0);
        assertThat(tool.isCustom()).isFalse();
        assertThat(tool.getName()).isEqualTo("apply_patch");
        assertThat(tool.getParameters()).containsEntry("required", List.of("input"));
        assertThat(tool.getDescription()).contains("Edits files.").contains("lark grammar").contains("start: x");
    }

    @Test
    void aCustomTool_staysCustomForAProviderThatTakesThem() {
        LlmProvider openAi = mock(LlmProvider.class);
        when(openAi.takesCustomTools()).thenReturn(true);

        assertThat(ProviderShapes.forProvider(openAi, withPatch()).getTools().get(0).isCustom()).isTrue();
    }

    private static ChatRequest withToolImage() {
        MultimodalMessage call = MultimodalMessage.builder().role("assistant").content(List.of())
                .toolCalls(List.of(new com.dvarahq.core.model.ToolCall("call_1", "view_image", "{}"),
                        new com.dvarahq.core.model.ToolCall("call_2", "read", "{}")))
                .build();
        MultimodalMessage image = MultimodalMessage.builder().role("tool").toolCallId("call_1")
                .content(List.of(new ContentBlock.ImageBlock("image/png", "AAAA", null))).build();
        return ChatRequest.builder().model("m").messages(List.of(MultimodalMessage.user("look"), call, image,
                MultimodalMessage.toolResult("call_2", "text"), MultimodalMessage.user("and?"))).build();
    }

    @Test
    void anImageInAToolResult_movesToAUserMessageAfterTheResults() {
        List<MultimodalMessage> messages = ProviderShapes.forProvider(mock(LlmProvider.class), withToolImage()).getMessages();

        assertThat(messages).extracting(MultimodalMessage::getRole)
                .containsExactly("user", "assistant", "tool", "tool", "user", "user");
        assertThat(messages.get(2).getContent()).noneMatch(b -> b instanceof ContentBlock.ImageBlock);
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_1");
        assertThat(messages.get(4).getContent()).hasSize(2);
        assertThat(messages.get(4).getContent().get(0).textContent()).contains("call_1");
        assertThat(messages.get(4).getContent().get(1)).isInstanceOf(ContentBlock.ImageBlock.class);
    }

    @Test
    void anImageInAToolResult_staysForAProviderThatTakesIt() {
        LlmProvider anthropic = mock(LlmProvider.class);
        when(anthropic.takesImagesInToolResults()).thenReturn(true);
        ChatRequest request = withToolImage();

        assertThat(ProviderShapes.forProvider(anthropic, request)).isSameAs(request);
    }

    @Test
    void reasoningEffortLeftOutForAnotherProvider_isLoggedWithTheReason() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProviderShapes.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ch.qos.logback.classic.Level before = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            LlmProvider claude = mock(LlmProvider.class);
            when(claude.name()).thenReturn("anthropic");
            ChatRequest request = ChatRequest.builder().model("m").messages(List.of(MultimodalMessage.user("hi")))
                    .reasoningEffort("high").build();

            assertThat(ProviderShapes.forProvider(claude, request)).isSameAs(request);
            assertThat(appender.list).singleElement().satisfies(e -> {
                assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.DEBUG);
                assertThat(e.getFormattedMessage()).contains("anthropic").contains("no such setting")
                        .contains("reasoning effort high");
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}
