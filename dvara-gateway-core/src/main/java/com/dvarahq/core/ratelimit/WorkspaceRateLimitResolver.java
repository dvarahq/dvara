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

/**
 * Resolves a workspace's per-key rate-limit override from {@code Workspace.metadata}
 * ({@code rate-limit.requests-per-minute} / {@code rate-limit.tokens-per-minute}), mirroring the
 * {@code priority-tier} resolution pattern. Returns {@link EffectiveRateLimit#NONE} when the workspace has
 * no override (or is unknown / null), so the data-plane limiter falls back to the global default.
 *
 * <p>Implementations cache the lookup on a short TTL — the resolve call is on the hot {@code /v1/*} path.
 * The metadata-backed implementation returns {@code NONE} for a workspace with no override, and
 * for every workspace when no workspace repository is configured.
 */
public interface WorkspaceRateLimitResolver {

    EffectiveRateLimit resolve(String workspaceId);
}