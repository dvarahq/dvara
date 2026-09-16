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
package com.dvarahq.core.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared ObjectMapper singleton for JSON serialization/deserialization.
 *
 * <p>Uses default Jackson configuration. Classes needing special configuration
 * (YAML factory, JavaTimeModule, ordered keys) should keep their own instance.</p>
 */
public final class JsonMapper {

    private static final ObjectMapper INSTANCE = new ObjectMapper()
            // Unknown properties are ignored: this mapper reads data a different version of our own
            // code wrote, and it parses responses from third-party MCP servers, where a foreign
            // server adding a field must not break a tool sync.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonMapper() {}

    public static ObjectMapper instance() {
        return INSTANCE;
    }
}