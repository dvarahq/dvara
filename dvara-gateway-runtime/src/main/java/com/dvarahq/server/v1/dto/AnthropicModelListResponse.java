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
package com.dvarahq.server.v1.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Anthropic's {@code GET /v1/models} shape, for a client built for the Anthropic API.
 *
 * @param data    the models, in the order the providers list them
 * @param hasMore always false: the whole list is one page
 * @param firstId the id of the first model, or null when the list is empty
 * @param lastId  the id of the last model, or null when the list is empty
 */
public record AnthropicModelListResponse(
        List<Model> data,
        @JsonProperty("has_more") boolean hasMore,
        @JsonProperty("first_id") String firstId,
        @JsonProperty("last_id") String lastId) {

    /**
     * One model.
     *
     * @param type        always {@code "model"}
     * @param id          the model id a request names
     * @param displayName the name a client shows; the id, since providers list no other name
     * @param createdAt   when the model was released, RFC 3339; the epoch when the provider does not say
     */
    public record Model(
            String type,
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("created_at") String createdAt) {
    }
}
