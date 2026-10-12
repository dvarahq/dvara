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
            mock(GatewayMetrics.class), mock(TokenEstimator.class), TestProviders.of(CallOutcomeListener.NOOP),
            TestProviders.<com.dvarahq.core.filter.StreamStopCheck>of(null), 120_000L);

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
    void input_imageItem_keepsItsUrlAndDetail() {
        // The Responses API puts detail on the part; a URL stays a URL.
        Map<String, Object> imagePart = Map.of("type", "input_image",
                "image_url", "https://example.com/sofa.jpg", "detail", "low");
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("gpt-4o")
                .input(List.of(Map.of("role", "user", "content", List.of(imagePart)))).build());

        ContentBlock.ImageBlock img = (ContentBlock.ImageBlock) internal.getMessages().get(0).getContent().get(0);
        assertThat(img.isUrl()).isTrue();
        assertThat(img.data()).isEqualTo("https://example.com/sofa.jpg");
        assertThat(img.detail()).isEqualTo("low");
    }

    @Test
    void chatImagePart_readsDetailFromImageUrl() {
        // Chat Completions puts detail inside image_url.
        ContentBlock.ImageBlock img = (ContentBlock.ImageBlock) ChatInputs.chatPart(Map.of("type", "image_url",
                "image_url", Map.of("url", "data:image/png;base64,AAAA", "detail", "low")));
        assertThat(img.mediaType()).isEqualTo("image/png");
        assertThat(img.data()).isEqualTo("AAAA");
        assertThat(img.detail()).isEqualTo("low");

        ContentBlock.ImageBlock none = (ContentBlock.ImageBlock) ChatInputs.chatPart(Map.of("type", "image_url",
                "image_url", Map.of("url", "data:image/png;base64,AAAA")));
        assertThat(none.detail()).isNull();
    }

    @Test
    void theEndUserIdMapsFromUserOrSafetyIdentifier() {
        // #54: dropped on /v1/responses, so it reached neither the provider nor cost attribution.
        assertThat(controller.toInternal(ResponseRequest.builder().model("gpt-4o").input("hi")
                .user("customer-42").build()).getUser()).isEqualTo("customer-42");
        assertThat(controller.toInternal(ResponseRequest.builder().model("gpt-4o").input("hi")
                .safetyIdentifier("sid-7").build()).getUser()).isEqualTo("sid-7");
        assertThat(controller.toInternal(ResponseRequest.builder().model("gpt-4o").input("hi")
                .user("customer-42").safetyIdentifier("sid-7").build()).getUser()).isEqualTo("sid-7");
        assertThat(controller.toInternal(ResponseRequest.builder().model("gpt-4o").input("hi")
                .user("  ").build()).getUser()).isNull();
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

    @Test
    void chatResponse_cachedAndReasoningTokens_mapToResponsesDetails() throws Exception {
        ChatResponse resp = ChatResponse.builder()
                .id("chatcmpl-1").model("o3").created(1234)
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.assistant("Four")).finishReason("stop").build()))
                .usage(ChatResponse.Usage.builder().promptTokens(1200).completionTokens(500).totalTokens(1700)
                        .cachedInputTokens(1024).reasoningTokens(448).build())
                .build();

        String json = com.dvarahq.core.util.JsonMapper.instance().writeValueAsString(controller.toResponseResult(resp));

        assertThat(json).contains("\"input_tokens_details\":{\"cached_tokens\":1024}")
                .contains("\"output_tokens_details\":{\"reasoning_tokens\":448}");
    }

    @Test
    void chatResponse_withoutBreakdown_hasZeroDetails_asOpenAiSendsThem() throws Exception {
        ChatResponse resp = ChatResponse.builder()
                .id("chatcmpl-1").model("gpt-4o").created(1234)
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.assistant("Four")).finishReason("stop").build()))
                .usage(ChatResponse.Usage.builder().promptTokens(5).completionTokens(1).totalTokens(6).build())
                .build();

        String json = com.dvarahq.core.util.JsonMapper.instance().writeValueAsString(controller.toResponseResult(resp));

        assertThat(json).contains("\"input_tokens_details\":{\"cached_tokens\":0}")
                .contains("\"output_tokens_details\":{\"reasoning_tokens\":0}");
    }

    // -------- function calling --------

    private static final Map<String, Object> WEATHER_TOOL = Map.of("type", "function", "name", "get_weather",
            "description", "Weather for a city",
            "parameters", Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))));

    @Test
    void functionTools_becomeToolDefinitions() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(WEATHER_TOOL)).toolChoice("auto").build());

        assertThat(internal.getTools()).hasSize(1);
        assertThat(internal.getTools().get(0).getName()).isEqualTo("get_weather");
        assertThat(internal.getTools().get(0).getDescription()).isEqualTo("Weather for a city");
        assertThat(internal.getTools().get(0).getParameters()).containsEntry("type", "object");
        assertThat(internal.getToolChoice()).isEqualTo("auto");
    }

    @Test
    void aBuiltInTool_isRefusedNamingItsType() {
        assertThatThrownBy(() -> controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(WEATHER_TOOL, Map.of("type", "web_search"))).build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"))
                .hasMessageContaining("web_search")
                .hasMessageContaining("runs at the provider");
    }

    @Test
    void aFunctionToolChoice_takesTheFormChatCarries() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(WEATHER_TOOL)).toolChoice(Map.of("type", "function", "name", "get_weather")).build());

        assertThat(internal.getToolChoice())
                .isEqualTo(Map.of("type", "function", "function", Map.of("name", "get_weather")));
    }

    @Test
    void aToolChoiceWithNoTools_isNotSent() {
        // Codex CLI sends tool_choice "auto" on every request; a provider refuses a choice without tools.
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .toolChoice("auto").build());

        assertThat(internal.getTools()).isNull();
        assertThat(internal.getToolChoice()).isNull();
    }

    @Test
    void anAllowedToolsChoice_isRefused() {
        assertThatThrownBy(() -> controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(WEATHER_TOOL))
                .toolChoice(Map.of("type", "allowed_tools", "mode", "auto", "tools", List.of())).build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"))
                .hasMessageContaining("allowed_tools");
    }

    @Test
    void functionCallItems_becomeOneAssistantTurnAndTheirOutputsToolMessages() {
        // Two calls of one turn, then their outputs: the shape every provider's tool loop expects.
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m")
                .tools(List.of(WEATHER_TOOL))
                .input(List.of(
                        Map.of("role", "user", "content", "Weather in Paris and Rome?"),
                        Map.of("type", "function_call", "id", "fc_1", "call_id", "call_1",
                                "name", "get_weather", "arguments", "{\"city\":\"Paris\"}"),
                        Map.of("type", "function_call", "id", "fc_2", "call_id", "call_2",
                                "name", "get_weather", "arguments", "{\"city\":\"Rome\"}"),
                        Map.of("type", "function_call_output", "call_id", "call_1", "output", "18C"),
                        Map.of("type", "function_call_output", "call_id", "call_2",
                                "output", List.of(Map.of("type", "input_text", "text", "21C")))))
                .build());

        List<MultimodalMessage> messages = internal.getMessages();
        assertThat(messages).extracting(MultimodalMessage::getRole)
                .containsExactly("user", "assistant", "tool", "tool");
        assertThat(messages.get(1).getToolCalls()).extracting(com.dvarahq.core.model.ToolCall::getId)
                .containsExactly("call_1", "call_2");
        assertThat(messages.get(1).getToolCalls().get(0).getName()).isEqualTo("get_weather");
        assertThat(messages.get(1).getToolCalls().get(0).getArguments()).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(messages.get(2).getToolCallId()).isEqualTo("call_1");
        assertThat(messages.get(2).textContent()).isEqualTo("18C");
        assertThat(messages.get(3).getToolCallId()).isEqualTo("call_2");
        assertThat(messages.get(3).textContent()).isEqualTo("21C");
    }

    @Test
    void aFunctionCallAfterTheAssistantsText_joinsThatMessage() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(
                        Map.of("role", "user", "content", "hi"),
                        Map.of("type", "message", "role", "assistant",
                                "content", List.of(Map.of("type", "output_text", "text", "Checking."))),
                        Map.of("type", "function_call", "call_id", "call_1", "name", "get_weather",
                                "arguments", "{}"),
                        Map.of("type", "function_call_output", "call_id", "call_1", "output", "18C")))
                .build());

        assertThat(internal.getMessages()).extracting(MultimodalMessage::getRole)
                .containsExactly("user", "assistant", "tool");
        assertThat(internal.getMessages().get(1).textContent()).isEqualTo("Checking.");
        assertThat(internal.getMessages().get(1).getToolCalls()).hasSize(1);
    }

    @Test
    void anInputItemTypeTheGatewayDoesNotRead_isRefusedNotSentAsAnEmptyMessage() {
        assertThatThrownBy(() -> controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(Map.of("role", "user", "content", "hi"),
                        Map.of("type", "item_reference", "id", "msg_1"))).build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"))
                .hasMessageContaining("item_reference");
    }

    @Test
    void aFunctionCallWithoutItsCallId_isInvalid() {
        assertThatThrownBy(() -> controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(Map.of("type", "function_call", "name", "get_weather", "arguments", "{}"))).build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("INVALID_REQUEST"))
                .hasMessageContaining("call_id");
    }

    @Test
    void anImageInAFunctionsOutput_isCarried() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(Map.of("type", "function_call_output", "call_id", "call_1",
                        "output", List.of(Map.of("type", "input_text", "text", "the screen:"),
                                Map.of("type", "input_image", "image_url", "data:image/png;base64,AAAA")))))
                .build());

        List<ContentBlock> blocks = internal.getMessages().get(0).getContent();
        assertThat(internal.getMessages().get(0).getRole()).isEqualTo("tool");
        assertThat(blocks.get(0).textContent()).isEqualTo("the screen:");
        assertThat(blocks.get(1)).isInstanceOf(ContentBlock.ImageBlock.class);
        assertThat(((ContentBlock.ImageBlock) blocks.get(1)).mediaType()).isEqualTo("image/png");
    }

    @Test
    void aFileInAFunctionsOutput_isRefused() {
        assertThatThrownBy(() -> controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(Map.of("type", "function_call_output", "call_id", "call_1",
                        "output", List.of(Map.of("type", "input_file", "file_id", "file-1")))))
                .build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"))
                .hasMessageContaining("input_file");
    }

    // -------- custom and namespace tools --------

    private static final Map<String, Object> PATCH_TOOL = Map.of("type", "custom", "name", "apply_patch",
            "description", "Edits files.", "format", Map.of("type", "grammar", "syntax", "lark", "definition", "start: x"));

    @Test
    void aCustomTool_isCarriedAsCustom() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(PATCH_TOOL)).build());

        com.dvarahq.core.model.ToolDefinition tool = internal.getTools().get(0);
        assertThat(tool.isCustom()).isTrue();
        assertThat(tool.getName()).isEqualTo("apply_patch");
        assertThat(tool.getFormat()).containsEntry("syntax", "lark");
    }

    @Test
    void aFunctionToolsStrict_isCarried() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(Map.of("type", "function", "name", "f", "strict", true,
                        "parameters", Map.of("type", "object")))).build());

        assertThat(internal.getTools().get(0).getStrict()).isTrue();
    }

    @Test
    void aNamespacesTools_travelUnderJoinedNames() {
        String longName = "x".repeat(70);
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(Map.of("type", "namespace", "name", "mcp__github", "description", "GitHub",
                        "tools", List.of(Map.of("type", "function", "name", "list_issues", "parameters", Map.of("type", "object")),
                                Map.of("type", "function", "name", longName, "parameters", Map.of("type", "object"))))))
                .build());

        assertThat(internal.getTools().get(0).getName()).isEqualTo("mcp__github__list_issues");
        assertThat(internal.getTools().get(1).getName()).hasSize(64).startsWith("mcp__github__xxx");
    }

    @Test
    void customAndNamespacedCallItems_travelUnderTheirToolsNames() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(Map.of("role", "user", "content", "go"),
                        Map.of("type", "custom_tool_call", "call_id", "call_1", "name", "apply_patch", "input", "*** Begin Patch"),
                        Map.of("type", "function_call", "call_id", "call_2", "namespace", "mcp__github",
                                "name", "list_issues", "arguments", "{}"),
                        Map.of("type", "custom_tool_call_output", "call_id", "call_1", "output", "Done"),
                        Map.of("type", "function_call_output", "call_id", "call_2", "output", "[]")))
                .build());

        List<com.dvarahq.core.model.ToolCall> calls = internal.getMessages().get(1).getToolCalls();
        assertThat(calls.get(0).getName()).isEqualTo("apply_patch");
        assertThat(calls.get(0).getArguments()).isEqualTo("{\"input\":\"*** Begin Patch\"}");
        assertThat(calls.get(1).getName()).isEqualTo("mcp__github__list_issues");
        assertThat(internal.getMessages()).extracting(MultimodalMessage::getRole)
                .containsExactly("user", "assistant", "tool", "tool");
    }

    @Test
    void webSearch_isRefusedTellingCodexUsersWhatToSet() {
        assertThatThrownBy(() -> controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(Map.of("type", "web_search", "external_web_access", false))).build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("web_search = \"disabled\"");
    }

    @Test
    void customAndNamespacedCalls_comeBackUnderTheirOwnNames() {
        ResponseRequest request = ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(PATCH_TOOL, Map.of("type", "namespace", "name", "mcp__github", "description", "",
                        "tools", List.of(Map.of("type", "function", "name", "list_issues"))))).build();
        MultimodalMessage msg = MultimodalMessage.builder().role("assistant").content(List.of())
                .toolCalls(List.of(
                        new com.dvarahq.core.model.ToolCall("call_1", "apply_patch", "{\"input\":\"*** Begin Patch\"}"),
                        new com.dvarahq.core.model.ToolCall("call_2", "mcp__github__list_issues", "{}")))
                .build();
        ChatResponse resp = ChatResponse.builder().id("c").model("m").created(1)
                .choices(List.of(ChatResponse.Choice.builder().index(0).message(msg).build())).build();

        List<ResponseResult.OutputItem> out = controller.toResponseResult(resp,
                ResponsesTools.read(request.getTools()).names()).getOutput();

        assertThat(out.get(0).getType()).isEqualTo("custom_tool_call");
        assertThat(out.get(0).getName()).isEqualTo("apply_patch");
        assertThat(out.get(0).getInput()).isEqualTo("*** Begin Patch");
        assertThat(out.get(0).getArguments()).isNull();
        assertThat(out.get(1).getType()).isEqualTo("function_call");
        assertThat(out.get(1).getName()).isEqualTo("list_issues");
        assertThat(out.get(1).getNamespace()).isEqualTo("mcp__github");
    }

    @Test
    void aToolCallAnswer_becomesFunctionCallItems() {
        MultimodalMessage msg = MultimodalMessage.builder().role("assistant").content(List.of())
                .toolCalls(List.of(new com.dvarahq.core.model.ToolCall("call_1", "get_weather", "{\"city\":\"Paris\"}")))
                .build();
        ChatResponse resp = ChatResponse.builder().id("c").model("gpt-4o").created(1)
                .choices(List.of(ChatResponse.Choice.builder().index(0).message(msg).finishReason("tool_calls").build()))
                .build();

        ResponseResult result = controller.toResponseResult(resp);

        assertThat(result.getOutput()).hasSize(1);
        ResponseResult.OutputItem item = result.getOutput().get(0);
        assertThat(item.getType()).isEqualTo("function_call");
        assertThat(item.getId()).startsWith("fc_");
        assertThat(item.getCallId()).isEqualTo("call_1");
        assertThat(item.getName()).isEqualTo("get_weather");
        assertThat(item.getArguments()).isEqualTo("{\"city\":\"Paris\"}");
        assertThat(item.getStatus()).isEqualTo("completed");
    }

    @Test
    void textAndAToolCall_becomeAMessageThenAFunctionCall() {
        MultimodalMessage msg = MultimodalMessage.builder().role("assistant")
                .content(List.of(new ContentBlock.TextBlock("Checking.")))
                .toolCalls(List.of(new com.dvarahq.core.model.ToolCall("call_1", "get_weather", "{}")))
                .build();
        ChatResponse resp = ChatResponse.builder().id("c").model("gpt-4o").created(1)
                .choices(List.of(ChatResponse.Choice.builder().index(0).message(msg).build()))
                .build();

        assertThat(controller.toResponseResult(resp).getOutput()).extracting(ResponseResult.OutputItem::getType)
                .containsExactly("message", "function_call");
    }

    // -------- what agents such as Codex CLI send --------

    @Test
    void whatOnlyTunesOrAsksForReasoningOutput_isAccepted() {
        ResponseRequest r = ResponseRequest.builder().model("m").input("hi")
                .reasoning(Map.of("effort", "high", "summary", "auto")).include(List.of("reasoning.encrypted_content"))
                .parallelToolCalls(true).serviceTier("auto").store(false)
                .clientMetadata(Map.of("session_id", "s-1")).streamOptions(Map.of("reasoning_summary_delivery", "sequential_cutoff"))
                .build();

        controller.rejectUnsupported(r);
        ChatRequest internal = controller.toInternal(r);

        assertThat(internal.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void verbosityAndThePromptCacheKey_areCarried() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .text(Map.of("verbosity", "low")).promptCacheKey("thread-1").build());

        assertThat(internal.getVerbosity()).isEqualTo("low");
        assertThat(internal.getPromptCacheKey()).isEqualTo("thread-1");
        assertThat(internal.getResponseFormat()).isNull();
    }

    @Test
    void reasoningThatIsNotAnObject_isInvalid() {
        assertThatThrownBy(() -> controller.rejectUnsupported(ResponseRequest.builder().model("m").input("hi")
                .reasoning("high").build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("INVALID_REQUEST"));
    }

    @Test
    void aDeveloperMessage_isASystemMessage() {
        // Providers other than OpenAI know no developer role, and some refuse it.
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(Map.of("type", "message", "role", "developer",
                                "content", List.of(Map.of("type", "input_text", "text", "Be terse."))),
                        Map.of("role", "user", "content", "hi")))
                .build());

        assertThat(internal.getMessages()).extracting(MultimodalMessage::getRole).containsExactly("system", "user");
    }

    @Test
    void aReasoningItem_isLeftOut() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m")
                .input(List.of(Map.of("role", "user", "content", "hi"),
                        Map.of("type", "reasoning", "id", "rs_1", "summary", List.of(), "encrypted_content", "opaque"),
                        Map.of("type", "function_call", "call_id", "call_1", "name", "f", "arguments", "{}"),
                        Map.of("type", "function_call_output", "call_id", "call_1", "output", "ok")))
                .build());

        assertThat(internal.getMessages()).extracting(MultimodalMessage::getRole)
                .containsExactly("user", "assistant", "tool");
        assertThat(internal.getMessages()).allSatisfy(m -> assertThat(m.textContent()).doesNotContain("opaque"));
    }

    // -------- reject unsupported --------

    @Test
    void unsupportedFeatures_rejectWithUnsupportedCapability() {
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").store(true).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").previousResponseId("resp_1").build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").reasoning(Map.of("mode", "deep")).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi")
                .include(List.of("reasoning.encrypted_content", "message.output_text.logprobs")).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").serviceTier("priority").build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").background(true).build());
        assertUnsupported(ResponseRequest.builder().model("m").input("hi").prompt(Map.of("id", "p_1")).build());
    }

    private void assertUnsupported(ResponseRequest req) {
        assertThatThrownBy(() -> controller.rejectUnsupported(req))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"));
    }

    // -------- tool search, and tool types only OpenAI's Responses API takes --------

    @Test
    void aClientRunToolSearch_isAFunction_andItsCallAndOutputAreATurn() {
        ResponseRequest r = ResponseRequest.builder().model("m")
                .tools(List.of(Map.of("type", "tool_search", "execution", "client", "description", "Find tools.",
                        "parameters", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))))
                .input(List.of(Map.of("role", "user", "content", "spawn a helper"),
                        Map.of("type", "tool_search_call", "call_id", "ts_1", "execution", "client",
                                "arguments", Map.of("query", "agents"), "status", "completed"),
                        Map.of("type", "tool_search_output", "call_id", "ts_1", "execution", "client", "status", "completed",
                                "tools", List.of(Map.of("type", "function", "name", "spawn_agent",
                                        "parameters", Map.of("type", "object"))))))
                .build();

        ChatRequest internal = controller.toInternal(r);

        assertThat(internal.getTools()).extracting(com.dvarahq.core.model.ToolDefinition::getName)
                .containsExactly("tool_search", "spawn_agent");
        assertThat(internal.getMessages()).extracting(MultimodalMessage::getRole).containsExactly("user", "assistant", "tool");
        assertThat(internal.getMessages().get(1).getToolCalls().get(0).getArguments()).isEqualTo("{\"query\":\"agents\"}");
        assertThat(internal.getMessages().get(2).textContent()).contains("spawn_agent");
        assertThat(internal.getResponsesApiOnly()).isEmpty();
    }

    @Test
    void aToolSearchCall_comesBackAsOne() {
        ResponseRequest request = ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(Map.of("type", "tool_search", "execution", "client"))).build();
        MultimodalMessage msg = MultimodalMessage.builder().role("assistant").content(List.of())
                .toolCalls(List.of(new com.dvarahq.core.model.ToolCall("ts_1", "tool_search", "{\"query\":\"agents\"}")))
                .build();
        ChatResponse resp = ChatResponse.builder().id("c").model("m").created(1)
                .choices(List.of(ChatResponse.Choice.builder().index(0).message(msg).build())).build();

        ResponseResult.OutputItem item = controller.toResponseResult(resp, ResponsesController.readTools(request).names())
                .getOutput().get(0);

        assertThat(item.getType()).isEqualTo("tool_search_call");
        assertThat(item.getExecution()).isEqualTo("client");
        assertThat(item.getArguments()).isEqualTo(Map.of("query", "agents"));
    }

    @Test
    void aToolTypeTheGatewayCannotTranslate_isLeftForOpenAisResponsesApi() {
        ChatRequest internal = controller.toInternal(ResponseRequest.builder().model("m").input("hi")
                .tools(List.of(Map.of("type", "function", "name", "f"), Map.of("type", "shell"),
                        Map.of("type", "tool_search", "execution", "server"))).build());

        assertThat(internal.getTools()).extracting(com.dvarahq.core.model.ToolDefinition::getName).containsExactly("f");
        assertThat(internal.getResponsesApiOnly()).containsExactly("the tool type shell", "a tool_search the provider runs");
    }

    @Test
    void parallelToolCallsFalseAndReasoningContext_areAccepted() {
        ResponseRequest r = ResponseRequest.builder().model("m").input("hi").parallelToolCalls(false)
                .reasoning(Map.of("effort", "low", "context", "all_turns")).build();

        controller.rejectUnsupported(r);
        assertThat(controller.toInternal(r).getParallelToolCalls()).isFalse();
    }
}
