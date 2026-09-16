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
package com.dvarahq.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A tool call the model emitted — the assistant's request to invoke a
 * function, relayed back to the client whose framework executes it and returns
 * the result on the next turn (as a {@code tool}-role message carrying the
 * matching {@link #id} in {@code toolCallId}).
 *
 * <p>{@link #arguments} is the raw JSON string the model produced, relayed
 * verbatim — the gateway does not parse or validate it (faithful-pipe boundary).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /** Provider-assigned id correlating this call with its later {@code tool} result. */
    private String id;

    /** The function name the model chose to call. */
    private String name;

    /** The model-produced arguments as a raw JSON string, relayed as-is. */
    private String arguments;
}