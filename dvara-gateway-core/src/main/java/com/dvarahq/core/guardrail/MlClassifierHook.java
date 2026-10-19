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


import java.util.Optional;

/**
 * Pluggable ML classification hook for injection and jailbreak detection.
 *
 * <p>This build includes no implementation and no no-op bean; another module or the application
 * may register one, in-process or hosted, and the composite detector picks it up. The injection
 * detector takes the hook optionally and checks {@link #isAvailable()}, so without one it detects
 * injection by its regex patterns alone. It sits in core rather than in the policy module so that
 * an implementation needs nothing but core.</p>
 */
public interface MlClassifierHook {

    Optional<GuardrailDetection> classify(String text);

    /**
     * The same question for a scan on behalf of {@code workspaceId} (null when the scan has no workspace). A hook that
     * keeps state per workspace, such as a result cache, overrides this; by default it asks {@link #classify(String)}.
     */
    default Optional<GuardrailDetection> classify(String text, String workspaceId) {
        return classify(text);
    }

    boolean isAvailable();

    /**
     * The version of the classifier that answers, for example a model name with its checksum; empty when unknown. A
     * result kept from an earlier version must not be reused, so a cache puts this in its key.
     */
    default String version() {
        return "";
    }
}