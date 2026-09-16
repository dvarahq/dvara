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
package com.dvarahq.ratelimit;

import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.WorkspaceRateLimitResolver;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a workspace's per-key rate-limit override.
 *
 * <p>The typed limits store is consulted first; otherwise {@code rate-limit.requests-per-minute}
 * and {@code rate-limit.tokens-per-minute} are read from {@code Workspace.metadata}. A missing key
 * means no override for that dimension. A value that is not a positive whole number is warned
 * about and ignored, so a stray {@code 0} can never black-hole a workspace.
 *
 * <p>This is the workspace level only; team and account ceilings are a separate mechanism and are
 * not read here. Results are cached for a short time, five seconds by default, because this runs
 * on every {@code /v1} request.
 */
public class MetadataWorkspaceRateLimitResolver implements WorkspaceRateLimitResolver {

    private static final Logger log = LoggerFactory.getLogger(MetadataWorkspaceRateLimitResolver.class);

    static final String REQUESTS_KEY = "rate-limit.requests-per-minute";
    static final String TOKENS_KEY = "rate-limit.tokens-per-minute";

    private final WorkspaceRepository workspaceRepository;
    private final long cacheTtlMs;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final com.dvarahq.core.workspace.settings.WorkspaceRateLimitsRepository limits;

    public MetadataWorkspaceRateLimitResolver(WorkspaceRepository workspaceRepository, long cacheTtlSeconds) {
        this(workspaceRepository, cacheTtlSeconds, null);
    }

    /**
     * @param limits the typed limits store, or {@code null} to read the metadata keys only, which
     *               is what a context without a datasource has.
     */
    public MetadataWorkspaceRateLimitResolver(WorkspaceRepository workspaceRepository, long cacheTtlSeconds,
                                              com.dvarahq.core.workspace.settings.WorkspaceRateLimitsRepository limits) {
        this.workspaceRepository = workspaceRepository;
        this.cacheTtlMs = cacheTtlSeconds * 1000L;
        this.limits = limits;
    }

    @Override
    public EffectiveRateLimit resolve(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return EffectiveRateLimit.NONE;
        }

        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(workspaceId);
        if (entry != null && now < entry.expiresAt) {
            return entry.override;
        }

        EffectiveRateLimit override = resolveFromRepository(workspaceId);
        cache.put(workspaceId, new CacheEntry(override, now + cacheTtlMs));
        return override;
    }

    /**
     * The typed store first, then the metadata map, with exactly one read either way. This runs on
     * every request, so a lookup that consulted both would double the reads.
     */
    private EffectiveRateLimit resolveFromRepository(String workspaceId) {
        if (limits != null) {
            var stored = limits.findByWorkspaceId(workspaceId).orElse(null);
            if (stored != null) {
                return effective(stored.requestsPerMinute(), stored.tokensPerMinute());
            }
        }
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            return EffectiveRateLimit.NONE;
        }
        var fromMap = com.dvarahq.core.workspace.settings.WorkspaceRateLimits.fromMetadata(
                workspaceId, workspace.getMetadata());
        warnAboutIgnoredLegacyValue(workspace, fromMap.requestsPerMinute(), REQUESTS_KEY);
        warnAboutIgnoredLegacyValue(workspace, fromMap.tokensPerMinute(), TOKENS_KEY);
        return effective(fromMap.requestsPerMinute(), fromMap.tokensPerMinute());
    }

    private static EffectiveRateLimit effective(Integer requests, Integer tokens) {
        int r = requests == null ? 0 : requests;
        int t = tokens == null ? 0 : tokens;
        return r <= 0 && t <= 0 ? EffectiveRateLimit.NONE : new EffectiveRateLimit(r, t);
    }

    /**
     * Warns about a metadata value that is not a positive whole number. The typed store validates
     * on write, so this covers only values still coming from the map.
     */
    private void warnAboutIgnoredLegacyValue(Workspace workspace, Integer resolved, String key) {
        if (resolved != null) {
            return;
        }
        Map<String, Object> metadata = workspace.getMetadata();
        if (metadata != null && metadata.get(key) != null) {
            log.warn("Ignoring rate-limit override {}={} for workspace {} — not a positive whole "
                    + "number, so the global default applies", key, metadata.get(key), workspace.getId());
        }
    }

    private record CacheEntry(EffectiveRateLimit override, long expiresAt) {
    }
}
