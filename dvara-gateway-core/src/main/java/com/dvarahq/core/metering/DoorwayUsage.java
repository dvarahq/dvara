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
package com.dvarahq.core.metering;

/**
 * Requests and tokens on one doorway over a window: what a usage page shows per API.
 *
 * @param doorway      a {@link Doorway#wireName()}, or {@link Doorway#UNKNOWN} for rows written before doorways
 *                     were recorded
 * @param requestCount usage rows on that doorway
 * @param inputTokens  input tokens, exact and estimated
 * @param outputTokens output tokens, exact and estimated
 * @param totalTokens  all tokens
 */
public record DoorwayUsage(String doorway, long requestCount, long inputTokens, long outputTokens, long totalTokens) {
}
