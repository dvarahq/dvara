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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMapperTest {

    @Test
    void instance_returnsSameObjectMapper() {
        ObjectMapper first = JsonMapper.instance();
        ObjectMapper second = JsonMapper.instance();
        assertThat(first).isSameAs(second);
    }

    @Test
    void instance_isNotNull() {
        assertThat(JsonMapper.instance()).isNotNull();
    }

    @Test
    void instance_canSerialize() throws Exception {
        String json = JsonMapper.instance().writeValueAsString(java.util.Map.of("key", "value"));
        assertThat(json).contains("\"key\"").contains("\"value\"");
    }

    @Test
    void instance_canDeserialize() throws Exception {
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> result = JsonMapper.instance()
                .readValue("{\"key\":\"value\"}", java.util.Map.class);
        assertThat(result).containsEntry("key", "value");
    }
}