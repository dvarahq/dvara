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
package com.dvarahq.core.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputSchemaResultTest {

    @Test
    void valid_isValid() {
        assertThat(OutputSchemaResult.VALID.valid()).isTrue();
        assertThat(OutputSchemaResult.VALID.errors()).isEmpty();
    }

    @Test
    void invalid_hasErrors() {
        OutputSchemaResult result = OutputSchemaResult.invalid(
                List.of("missing required field: name", "type mismatch for age"),
                "schema-123");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.schemaId()).isEqualTo("schema-123");
    }

    @Test
    void invalid_emptyErrorList_stillInvalid() {
        OutputSchemaResult result = OutputSchemaResult.invalid(List.of(), "schema-456");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isEmpty();
    }
}