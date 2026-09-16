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
package com.dvarahq.server.v1;

import com.dvarahq.server.TestProviders;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.ResponseRequest;
import com.dvarahq.server.v1.dto.ResponseResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Mapping between the Responses API DTOs and the internal model, tested on the controller's mapping methods without a Spring context. */
class ResponsesMappingTest {

    private final ResponsesController controller = new ResponsesController(
            mock(ProviderDispatcher.class), mock(RequestPipeline.class), TestProviders.of(mock(com.dvarahq.core.cache.ResponseCache.class)),
            mock(TokenUsageRepository.class), TestProviders.of(mock(WorkspaceUsageListener.class)), TestProviders.of(mock(CostCalculationService.class)),
            TestProviders.of(mock(CostEstimator.class)), mock(PiiEnforcer.class), mock(RateLimiter.class),
            mock(StreamingResponseEnforcer.class), mock(AuditWriter.class), TestProviders.of(mock(PriorityAdmissionController.class)),
            mock(GatewayMetrics.class), mock(TokenEstimator.class), TestProviders.of(CallOutcomeListener.NOOP), 120_000L);

    // -------- request -> ChatRequest --------

    @Test
    void input_plainString_becomesSingleUserMessage() {
        ChatRequest internal = controller.toInternal(
                ResponseRequest.builder().model("gpt-4o").input("Hello there").build());

        assertThat(internal.getModel()).isEqualTo("gpt-4o");
        assertThat(internal.getMessages()).hasSize(1);
        MultimodalMessage m = internal.getMessages().get(0);
        assertThat(m.getRole()).isEqualTo("user");
        assertThat(m.textContent()).isEqualTo("Hello there");
    }

    @Test
    void instructions_becomeLeadingSystemMessage() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder()
                .model("gpt-4o").instructions("You are terse.").input("Hi").build());

        assertThat(internal.getMessages()).hasSize(2);
        assertThat(internal.getMessages().get(0).getRole()).isEqualTo("system");
        assertThat(internal.getMessages().get(0).textContent()).isEqualTo("You are terse.");
        assertThat(internal.getMessages().get(1).getRole()).isEqualTo("user");
    }

    @Test
    void input_messageArray_mapsRoleAndContent() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("gpt-4o")
                .input(List.of(Map.of("role", "user", "content", "What is 2+2?"))).build());

        assertThat(internal.getMessages()).hasSize(1);
        assertThat(internal.getMessages().get(0).getRole()).isEqualTo("user");
        assertThat(internal.getMessages().get(0).textContent()).isEqualTo("What is 2+2?");
    }

    @Test
    void input_imageItem_becomesImageBlock() {
        Map<String, Object> imagePart = Map.of("type", "input_image",
                "image_url", "data:image/png;base64,AAAABBBB");
        Map<String, Object> textPart = Map.of("type", "input_text", "text", "describe");
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("gpt-4o")
                .input(List.of(Map.of("role", "user", "content", List.of(textPart, imagePart)))).build());

        List<ContentBlock> blocks = internal.getMessages().get(0).getContent();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).isInstanceOf(ContentBlock.TextBlock.class);
        assertThat(blocks.get(1)).isInstanceOf(ContentBlock.ImageBlock.class);
        ContentBlock.ImageBlock img = (ContentBlock.ImageBlock) blocks.get(1);
        assertThat(img.mediaType()).isEqualTo("image/png");
        assertThat(img.data()).isEqualTo("AAAABBBB");
    }

    @Test
    void scalarParams_mapToChatRequest() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("gpt-4o")
                .input("hi").maxOutputTokens(256).temperature(0.7).topP(0.9).stream(true).build());

        assertThat(internal.getMaxTokens()).isEqualTo(256);
        assertThat(internal.getTemperature()).isEqualTo(0.7);
        assertThat(internal.getTopP()).isEqualTo(0.9);
        assertThat(internal.isStream()).isTrue();
    }

    @Test
    void textFormat_jsonSchema_mapsToResponseFormat() {
        Map<String, Object> format = Map.of("type", "json_schema", "name", "person",
                "schema", Map.of("type", "object"), "strict", true);
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("gpt-4o")
                .input("hi").text(Map.of("format", format)).build());

        assertThat(internal.getResponseFormat()).isInstanceOf(ResponseFormat.JsonSchema.class);
        ResponseFormat.JsonSchema js = (ResponseFormat.JsonSchema) internal.getResponseFormat();
        assertThat(js.name()).isEqualTo("person");
        assertThat(js.strict()).isTrue();
    }

    @Test
    void textFormat_jsonObject_andText() {
        ChatRequest jo = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .text(Map.of("format", Map.of("type", "json_object"))).build());
        assertThat(jo.getResponseFormat()).isInstanceOf(ResponseFormat.JsonObject.class);

        ChatRequest txt = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .text(Map.of("format", Map.of("type", "text"))).build());
        assertThat(txt.getResponseFormat()).isInstanceOf(ResponseFormat.Text.class);
    }

    @Test
    void missingInputAndInstructions_isRejected() {
        assertThatThrownBy(() -> controller.toInternal(ResponseRequest.builder().model("gpt-4o").build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("INVALID_REQUEST"));
    }

    // -------- ChatResponse -> ResponseResult --------

    @Test
    void chatResponse_mapsToResponseResultShape() {
        ChatResponse resp = ChatResponse.builder()
                .id("chatcmpl-1").model("gpt-4o").created(1234)
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.assistant("Four")).finishReason("stop").build()))
                .usage(ChatResponse.Usage.builder().promptTokens(5).completionTokens(1).totalTokens(6).build())
                .build();

        ResponseResult result = controller.toResponseResult(resp);

        assertThat(result.getObject()).isEqualTo("response");
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getModel()).isEqualTo("gpt-4o");
        assertThat(result.getOutput()).hasSize(1);
        ResponseResult.OutputItem item = result.getOutput().get(0);
        assertThat(item.getType()).isEqualTo("message");
        assertThat(item.getRole()).isEqualTo("assistant");
        assertThat(item.getContent().get(0).getType()).isEqualTo("output_text");
        assertThat(item.getContent().get(0).getText()).isEqualTo("Four");
        assertThat(result.getUsage().getInputTokens()).isEqualTo(5);
        assertThat(result.getUsage().getOutputTokens()).isEqualTo(1);
        assertThat(result.getUsage().getTotalTokens()).isEqualTo(6);
    }

    // -------- reject unsupported --------

    @Test
    void unsupportedFeatures_rejectWithUnsupportedCapability() {
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").store(true).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").previousResponseId("resp_1").build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").reasoning(Map.of("effort", "high")).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").background(true).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").prompt(Map.of("id", "p_1")).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(Map.of("type", "web_search"))).build());
    }

    private void assertUnsupported(ResponseRequest req) {
        assertThatThrownBy(() -> controller.rejectUnsupported(req))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"));
    }
}