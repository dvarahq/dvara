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
package com.dvarahq.autoconfigure.cache;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryResponseCacheTest {

    private static ChatRequest request(String workspaceId, String model, String text) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock(text)))
                        .build()))
                .metadata(Map.of("workspace_id", workspaceId))
                .build();
    }

    private static ChatResponse response(String id) {
        return ChatResponse.builder().id(id).build();
    }

    @Test
    void anIdenticalRequestIsServedFromTheCache() {
        var cache = new InMemoryResponseCache(10, 300);
        cache.put(request("acme", "gpt-4o", "hello"), response("r1"));

        assertThat(cache.get(request("acme", "gpt-4o", "hello")))
                .map(ChatResponse::getId).contains("r1");
    }

    @Test
    void aDifferentWorkspaceGetsItsOwnEntry() {
        // The house rule for anything workspace-scoped is that the scope is in the lookup. A key
        // without it means one workspace's stored response is served to another by construction.
        var cache = new InMemoryResponseCache(10, 300);
        cache.put(request("acme", "gpt-4o", "hello"), response("acme-answer"));

        assertThat(cache.get(request("globex", "gpt-4o", "hello")))
                .as("an identical prompt from another workspace is not this workspace's answer")
                .isEmpty();
    }

    @Test
    void aDifferentModelOrPromptIsADifferentCall() {
        var cache = new InMemoryResponseCache(10, 300);
        cache.put(request("acme", "gpt-4o", "hello"), response("r1"));

        assertThat(cache.get(request("acme", "claude-3-opus", "hello"))).isEmpty();
        assertThat(cache.get(request("acme", "gpt-4o", "hello there"))).isEmpty();
    }

    @Test
    void temperatureAndMaxTokensArePartOfTheIdentity() {
        var cache = new InMemoryResponseCache(10, 300);
        ChatRequest cold = request("acme", "gpt-4o", "hello").toBuilder().temperature(0.0).build();
        ChatRequest hot = request("acme", "gpt-4o", "hello").toBuilder().temperature(1.0).build();
        cache.put(cold, response("deterministic"));

        assertThat(cache.get(hot))
                .as("they ask the model for different things")
                .isEmpty();
        assertThat(cache.get(cold)).isPresent();
    }

    @Test
    void messageBoundariesCannotBeFlattenedIntoOneAnother() {
        // Length-prefixing is what stops two different message lists hashing to one string. Without
        // it a message ending at the separator collides with the next one starting.
        var cache = new InMemoryResponseCache(10, 300);
        ChatRequest twoMessages = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder().role("user")
                                .content(List.of(new ContentBlock.TextBlock("ab"))).build(),
                        MultimodalMessage.builder().role("user")
                                .content(List.of(new ContentBlock.TextBlock("c"))).build()))
                .metadata(Map.of("workspace_id", "acme")).build();

        cache.put(twoMessages, response("two"));

        assertThat(cache.get(request("acme", "gpt-4o", "abc")))
                .as("one message of 'abc' is not two messages of 'ab' and 'c'")
                .isEmpty();
    }

    @Test
    void evictRemovesTheEntry_soARefusedResponseStopsBeingServed() {
        // Implemented rather than left as the interface's no-op: without it the execution service
        // re-scans and re-refuses the same content on every later request.
        var cache = new InMemoryResponseCache(10, 300);
        ChatRequest r = request("acme", "gpt-4o", "hello");
        cache.put(r, response("r1"));

        cache.evict(r);

        assertThat(cache.get(r)).isEmpty();
    }

    @Test
    void clearDropsEverything() {
        var cache = new InMemoryResponseCache(10, 300);
        cache.put(request("acme", "gpt-4o", "one"), response("r1"));
        cache.put(request("acme", "gpt-4o", "two"), response("r2"));

        cache.clear();

        assertThat(cache.get(request("acme", "gpt-4o", "one"))).isEmpty();
        assertThat(cache.get(request("acme", "gpt-4o", "two"))).isEmpty();
    }

    @Test
    void anExpiredEntryIsNotServed() {
        var cache = new InMemoryResponseCache(10, 0);
        ChatRequest r = request("acme", "gpt-4o", "hello");
        cache.put(r, response("r1"));

        assertThat(cache.get(r)).isEmpty();
    }

    @Test
    void aNullResponseIsNotStored() {
        var cache = new InMemoryResponseCache(10, 300);
        ChatRequest r = request("acme", "gpt-4o", "hello");

        cache.put(r, null);

        assertThat(cache.get(r)).isEmpty();
    }

    @Test
    void aResponseFormatIsPartOfTheIdentity() {
        var cache = new InMemoryResponseCache(10, 300);
        ChatRequest plain = request("acme", "gpt-4o", "hello");
        cache.put(plain, response("prose"));

        ChatRequest schema = plain.toBuilder()
                .responseFormat(new ResponseFormat.JsonSchema("answer", Map.of("type", "object"), true))
                .build();
        assertThat(cache.get(schema))
                .as("a request for JSON is not answered with the prose stored for the same prompt")
                .isEmpty();
    }

    @Test
    void aSchemaWithItsKeysInAnotherOrderIsTheSameFormat() {
        var cache = new InMemoryResponseCache(10, 300);
        Map<String, Object> typeFirst = new LinkedHashMap<>();
        typeFirst.put("type", "object");
        typeFirst.put("required", List.of("city"));
        Map<String, Object> requiredFirst = new LinkedHashMap<>();
        requiredFirst.put("required", List.of("city"));
        requiredFirst.put("type", "object");
        ChatRequest first = request("acme", "gpt-4o", "hello").toBuilder()
                .responseFormat(new ResponseFormat.JsonSchema("answer", typeFirst, true)).build();
        ChatRequest second = request("acme", "gpt-4o", "hello").toBuilder()
                .responseFormat(new ResponseFormat.JsonSchema("answer", requiredFirst, true)).build();
        cache.put(first, response("json"));

        assertThat(cache.get(second)).isPresent();
    }

    @Test
    void toolsAndToolChoiceArePartOfTheIdentity() {
        var cache = new InMemoryResponseCache(10, 300);
        ChatRequest plain = request("acme", "gpt-4o", "weather in Paris?");
        cache.put(plain, response("no-tools"));

        ToolDefinition weather = ToolDefinition.builder().name("get_weather")
                .parameters(Map.of("type", "object")).build();
        ChatRequest withTools = plain.toBuilder().tools(List.of(weather)).build();
        assertThat(cache.get(withTools))
                .as("an answer produced without tools is not served to a request that has them")
                .isEmpty();

        cache.put(withTools, response("auto"));
        assertThat(cache.get(withTools.toBuilder().toolChoice("none").build()))
                .as("forbidding the tools asks the model for something else")
                .isEmpty();
    }

    @Test
    void topPThePenaltiesStopAndSeedArePartOfTheIdentity() {
        var cache = new InMemoryResponseCache(10, 300);
        ChatRequest base = request("acme", "gpt-4o", "hello");
        cache.put(base, response("r1"));

        assertThat(cache.get(base.toBuilder().topP(0.1).build())).as("top_p").isEmpty();
        assertThat(cache.get(base.toBuilder().frequencyPenalty(1.0).build())).as("frequency_penalty").isEmpty();
        assertThat(cache.get(base.toBuilder().presencePenalty(1.0).build())).as("presence_penalty").isEmpty();
        assertThat(cache.get(base.toBuilder().stop(List.of("END")).build())).as("stop").isEmpty();
        assertThat(cache.get(base.toBuilder().seed(7L).build())).as("seed").isEmpty();
        assertThat(cache.get(base)).isPresent();
    }

    @Test
    void toolCallsAndToolResultsArePartOfTheIdentity() {
        var cache = new InMemoryResponseCache(10, 300);
        cache.put(conversation("call_1", "call_1", "{\"city\":\"Paris\"}"), response("paris"));

        assertThat(cache.get(conversation("call_1", "call_1", "{\"city\":\"Rome\"}")))
                .as("the same question after a call with different arguments")
                .isEmpty();
        assertThat(cache.get(conversation("call_1", "call_9", "{\"city\":\"Paris\"}")))
                .as("a tool result that answers a different call")
                .isEmpty();
        assertThat(cache.get(conversation("call_1", "call_1", "{\"city\":\"Paris\"}"))).isPresent();
    }

    @Test
    void theSameImageIsTheSameRequestAndADifferentImageIsNot() {
        var cache = new InMemoryResponseCache(10, 300);
        cache.put(withImage("aGVsbG8="), response("cat"));

        assertThat(cache.get(withImage("aGVsbG8=")))
                .as("the same picture sent again, as a new object")
                .isPresent();
        assertThat(cache.get(withImage("d29ybGQ=")))
                .as("a different picture with the same question")
                .isEmpty();
    }

    private static ChatRequest conversation(String callId, String resultFor, String arguments) {
        return ChatRequest.builder().model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder().role("user")
                                .content(List.of(new ContentBlock.TextBlock("weather?"))).build(),
                        MultimodalMessage.builder().role("assistant")
                                .toolCalls(List.of(ToolCall.builder()
                                        .id(callId).name("get_weather").arguments(arguments).build()))
                                .build(),
                        MultimodalMessage.builder().role("tool").toolCallId(resultFor)
                                .content(List.of(new ContentBlock.TextBlock("sunny"))).build()))
                .metadata(Map.of("workspace_id", "acme"))
                .build();
    }

    private static ChatRequest withImage(String data) {
        return ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder().role("user")
                        .content(List.of(new ContentBlock.TextBlock("what is this?"),
                                new ContentBlock.ImageBlock("image/png", data)))
                        .build()))
                .metadata(Map.of("workspace_id", "acme"))
                .build();
    }
}
