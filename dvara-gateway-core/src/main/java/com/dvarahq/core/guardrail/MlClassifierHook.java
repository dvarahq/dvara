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

    boolean isAvailable();
}