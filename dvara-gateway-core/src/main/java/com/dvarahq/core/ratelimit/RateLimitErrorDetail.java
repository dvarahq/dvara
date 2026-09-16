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
package com.dvarahq.core.ratelimit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * The structured detail a 429 carries, under {@code error.rate_limit}.
 *
 * <p><b>Which fields are filled depends on the plane</b>, because one record serves both and each
 * plane renders its own body. The LLM plane's limiters fill {@code limitedResource},
 * {@code limitType}, {@code limit}, {@code remaining}, {@code resetAt} and
 * {@code retryAfterSeconds}. The MCP plane's filter fills {@code serverId} and
 * {@code alternativeServers} — other registered servers carrying the same tags that are not
 * themselves limited — and those two are read only there.
 *
 * <p>There is deliberately no {@code alternativeModels} field: choosing a model to recommend needs
 * a notion of which models this workspace may use and what they cost, and that is a feature, not a
 * field on a 429.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitErrorDetail {

    private String limitedResource;
    private String serverId;
    private String limitType;
    private long limit;
    private long remaining;
    private Instant resetAt;
    private long retryAfterSeconds;
    private List<String> alternativeServers;
}