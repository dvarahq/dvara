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
package com.dvarahq.core.policy;

/**
 * A policy changed somewhere, so an engine holding a compiled index should rebuild it.
 *
 * <p><b>{@code policyId} is informational.</b> The only consumer logs it and then rebuilds the whole
 * index, so nothing is scoped by it, and some publishers pass {@code "*"} to mean "a bulk refresh,
 * no single policy". Do not build a scoped rebuild on it without checking every publisher sends a
 * real id.
 */
public record PolicyMutationEvent(String policyId) {
}