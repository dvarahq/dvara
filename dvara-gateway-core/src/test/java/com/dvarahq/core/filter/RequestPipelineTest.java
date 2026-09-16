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
package com.dvarahq.core.filter;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestPipelineTest {

    @Test
    void preDispatch_executesFiltersInOrder() {
        var executionOrder = new ArrayList<String>();

        ChatFilter filterA = new ChatFilter() {
            @Override public int order() { return 200; }
            @Override public ChatRequest preDispatch(ChatRequest req, FilterContext ctx) {
                executionOrder.add("A");
                return req;
            }
        };
        ChatFilter filterB = new ChatFilter() {
            @Override public int order() { return 100; }
            @Override public ChatRequest preDispatch(ChatRequest req, FilterContext ctx) {
                executionOrder.add("B");
                return req;
            }
        };

        var pipeline = new RequestPipeline(List.of(filterA, filterB));
        pipeline.preDispatch(ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").build());

        assertThat(executionOrder).containsExactly("B", "A"); // B first (order 100)
    }

    @Test
    void postDispatch_executesFiltersInReverseOrder() {
        var executionOrder = new ArrayList<String>();

        ChatFilter filterA = new ChatFilter() {
            @Override public int order() { return 100; }
            @Override public ChatResponse postDispatch(ChatRequest req, ChatResponse res, FilterContext ctx) {
                executionOrder.add("A");
                return res;
            }
        };
        ChatFilter filterB = new ChatFilter() {
            @Override public int order() { return 200; }
            @Override public ChatResponse postDispatch(ChatRequest req, ChatResponse res, FilterContext ctx) {
                executionOrder.add("B");
                return res;
            }
        };

        var pipeline = new RequestPipeline(List.of(filterA, filterB));
        pipeline.postDispatch(ChatRequest.builder().build(),
                ChatResponse.builder().build(),
                FilterContext.builder().build());

        assertThat(executionOrder).containsExactly("B", "A"); // B first (reverse of 100,200)
    }

    @Test
    void preDispatch_modifiesRequest() {
        ChatFilter templateFilter = new ChatFilter() {
            @Override public int order() { return 100; }
            @Override public ChatRequest preDispatch(ChatRequest req, FilterContext ctx) {
                return ChatRequest.builder().model("resolved-model")
                        .messages(req.getMessages()).build();
            }
        };

        var pipeline = new RequestPipeline(List.of(templateFilter));
        ChatRequest result = pipeline.preDispatch(
                ChatRequest.builder().model("original").build(),
                FilterContext.builder().build());

        assertThat(result.getModel()).isEqualTo("resolved-model");
    }

    @Test
    void preDispatch_filterThrows_propagates() {
        ChatFilter blockingFilter = new ChatFilter() {
            @Override public int order() { return 100; }
            @Override public ChatRequest preDispatch(ChatRequest req, FilterContext ctx) {
                throw new com.dvarahq.core.exception.GatewayException("BLOCKED", "denied");
            }
        };

        var pipeline = new RequestPipeline(List.of(blockingFilter));

        assertThatThrownBy(() -> pipeline.preDispatch(
                ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().build()))
                .isInstanceOf(com.dvarahq.core.exception.GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "BLOCKED");
    }

    @Test
    void filterContext_carriesStateBetweenFilters() {
        ChatFilter setter = new ChatFilter() {
            @Override public int order() { return 100; }
            @Override public ChatRequest preDispatch(ChatRequest req, FilterContext ctx) {
                ctx.setAttribute("custom_key", "custom_value");
                return req;
            }
        };
        ChatFilter reader = new ChatFilter() {
            @Override public int order() { return 200; }
            @Override public ChatRequest preDispatch(ChatRequest req, FilterContext ctx) {
                assertThat((String) ctx.getAttribute("custom_key")).isEqualTo("custom_value");
                return req;
            }
        };

        var pipeline = new RequestPipeline(List.of(setter, reader));
        pipeline.preDispatch(ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").build());
    }

    @Test
    void emptyPipeline_passesThroughUnchanged() {
        var pipeline = new RequestPipeline(List.of());
        ChatRequest req = ChatRequest.builder().model("gpt-4o").build();
        ChatRequest result = pipeline.preDispatch(req, FilterContext.builder().build());
        assertThat(result).isSameAs(req);
    }
}