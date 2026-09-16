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

/**
 * A governance filter in the chat request/response pipeline.
 * Filters are executed in order before dispatch (pre) and after dispatch (post).
 */
public interface ChatFilter {

    /**
     * Returns the filter's execution order. Lower values execute first.
     */
    int order();

    /**
     * Pre-dispatch: inspect/modify the request before it reaches the LLM provider.
     * May throw GatewayException to reject the request.
     *
     * @return the (possibly modified) request
     */
    default ChatRequest preDispatch(ChatRequest request, FilterContext ctx) {
        return request;
    }

    /**
     * Post-dispatch: inspect/modify the response before returning to the client.
     * May throw GatewayException to reject the response.
     *
     * @return the (possibly modified) response
     */
    default ChatResponse postDispatch(ChatRequest request, ChatResponse response, FilterContext ctx) {
        return response;
    }
}