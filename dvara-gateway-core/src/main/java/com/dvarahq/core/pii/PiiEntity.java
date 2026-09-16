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
package com.dvarahq.core.pii;

/**
 * A detected PII entity within text.
 *
 * @param type       the PII entity type
 * @param value      the matched text value
 * @param start      start offset in the source text (inclusive)
 * @param end        end offset in the source text (exclusive)
 * @param label      detector label (e.g. "email", "ssn", "credit_card")
 * @param confidence detection confidence (0.0–1.0)
 */
public record PiiEntity(
        PiiEntityType type,
        String value,
        int start,
        int end,
        String label,
        double confidence
) {}