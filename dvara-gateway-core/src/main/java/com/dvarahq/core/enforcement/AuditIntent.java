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
package com.dvarahq.core.enforcement;

import java.util.Map;

/**
 * An audit event the caller should write — <b>one per semantic intent</b>, not one per response.
 *
 * <p>It is an intent rather than a write because enforcement must have no side effects: computing the
 * result twice must cost nothing and change nothing. The caller writes these after acting on the
 * disposition, and a failure to write any of them is logged and changes no decision.</p>
 */
public record AuditIntent(String eventType, Map<String, Object> payload) {
    public AuditIntent {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
