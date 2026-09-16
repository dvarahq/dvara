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

import java.util.List;

/**
 * Result of validating a response against an output schema.
 *
 * @param valid    whether the response matches the schema
 * @param errors   list of validation error messages
 * @param schemaId identifier of the schema used for validation
 */
public record OutputSchemaResult(boolean valid, List<String> errors, String schemaId) {

    public static final OutputSchemaResult VALID = new OutputSchemaResult(true, List.of(), null);

    public static OutputSchemaResult invalid(List<String> errors, String schemaId) {
        return new OutputSchemaResult(false, errors, schemaId);
    }
}