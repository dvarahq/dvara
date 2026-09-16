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

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmbeddingRequest {
    private String model;
    /** String or list of strings. */
    private Object input;
    private String user;

    /**
     * How many dimensions the caller wants the vectors truncated to, relayed to upstreams that
     * support it and null when the caller did not ask.
     *
     * <p>There is deliberately no {@code encodingFormat} beside it. The only non-default value is
     * {@code base64}, which changes the response from an array of numbers to a string; this model's
     * {@code embedding} is a {@code List<Double>} and cannot carry it, so the doorway refuses that
     * value rather than claiming a format it did not apply.
     */
    private Integer dimensions;
}