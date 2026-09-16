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
package com.dvarahq.pii;

/**
 * Supported PII detection providers.
 */
public enum PiiProvider {

    /** Regex-based detection with checksum validation (default). */
    REGEX,

    /** An external NER analyzer. Nothing in this build calls one; a detector registered for the {@code presidio} layer does. */
    PRESIDIO;

    public static PiiProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return REGEX;
        }
        try {
            return valueOf(value.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return REGEX;
        }
    }
}