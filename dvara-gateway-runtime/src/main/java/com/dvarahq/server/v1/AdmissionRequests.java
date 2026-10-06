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

import com.dvarahq.core.model.AnthropicMessagesBody;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.v1.dto.ChatCompletionRequest;
import com.dvarahq.server.v1.dto.EmbeddingRequest;
import com.dvarahq.server.v1.dto.MessagesRequest;
import com.dvarahq.server.v1.dto.ResponseRequest;

import java.util.Optional;

/**
 * A request body read the way its endpoint reads it, before the request is admitted.
 *
 * <p>The rate limiter charges a call's input tokens when it admits it, before any controller runs. It
 * counts this request with the gateway's {@code TokenEstimator}, the count the guardrail's input cap,
 * the context-window check and the usage meter use too. Counting the raw body instead would charge the
 * JSON syntax and every byte of an inline image's base64.
 *
 * <p>Each endpoint's own mapping is used, so the request counted here is the one the controller builds.
 */
public final class AdmissionRequests {

    private AdmissionRequests() {
    }

    /**
     * @param path the request path
     * @param body the request body
     * @return the request to count, or empty when the path takes no tokens or the body is not a request
     *     the endpoint would accept; the endpoint refuses such a body itself
     */
    public static Optional<ChatRequest> forTokenCount(String path, byte[] body) {
        if (path == null || body == null || body.length == 0) {
            return Optional.empty();
        }
        try {
            if (path.endsWith("/chat/completions")) {
                return Optional.of(ChatCompletionController.toInternal(
                        JsonMapper.instance().readValue(body, ChatCompletionRequest.class)));
            }
            if (path.endsWith("/v1/responses")) {
                return Optional.of(ResponsesController.toInternal(
                        JsonMapper.instance().readValue(body, ResponseRequest.class)));
            }
            if (path.endsWith("/v1/messages")) {
                AnthropicMessagesBody messages = AnthropicMessagesBody.parse(body);
                return Optional.of(AnthropicMessages.toInternal(
                        messages.bind(MessagesRequest.class), null, messages));
            }
            if (path.endsWith("/v1/embeddings")) {
                return Optional.of(EmbeddingController.governanceView(
                        JsonMapper.instance().readValue(body, EmbeddingRequest.class)));
            }
        } catch (Exception e) {
            // Not a request the endpoint accepts. It refuses the body with the reason; nothing is
            // charged for it here.
            return Optional.empty();
        }
        return Optional.empty();
    }
}
