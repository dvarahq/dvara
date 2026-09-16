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
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tier-1 unit proof for the per-workspace rate-limit override resolver: override present / absent /
 * invalid, and the short-TTL cache. Pure Mockito — no infrastructure.
 */
class MetadataWorkspaceRateLimitResolverTest {

    private final WorkspaceRepository repo = mock(WorkspaceRepository.class);

    private MetadataWorkspaceRateLimitResolver resolver(long ttlSeconds) {
        return new MetadataWorkspaceRateLimitResolver(repo, ttlSeconds);
    }

    private void stubWorkspace(String id, Map<String, Object> metadata) {
        when(repo.findById(id)).thenReturn(Optional.of(
                Workspace.builder().id(id).name(id).metadata(metadata).build()));
    }

    @Test
    void bothDimensionsOverridden() {
        stubWorkspace("t1", Map.of(
                "rate-limit.requests-per-minute", 1000,
                "rate-limit.tokens-per-minute", 2_000_000));
        assertThat(resolver(5).resolve("t1")).isEqualTo(new EffectiveRateLimit(1000, 2_000_000));
    }

    @Test
    void stringNumericValuesAreParsed() {
        // Workspace.metadata round-trips through JSON, so numbers can arrive as strings
        stubWorkspace("t1", Map.of("rate-limit.requests-per-minute", "500"));
        assertThat(resolver(5).resolve("t1")).isEqualTo(new EffectiveRateLimit(500, 0));
    }

    @Test
    void longNumericValueIsParsed() {
        // JSONB round-trip through Jackson can hand back a Long rather than an Integer
        stubWorkspace("t1", Map.of("rate-limit.tokens-per-minute", 2_000_000L));
        assertThat(resolver(5).resolve("t1")).isEqualTo(new EffectiveRateLimit(0, 2_000_000));
    }

    @Test
    void onlyOneDimensionOverridden_otherStaysNone() {
        stubWorkspace("t1", Map.of("rate-limit.tokens-per-minute", 750_000));
        EffectiveRateLimit eff = resolver(5).resolve("t1");
        assertThat(eff.hasRequestOverride()).isFalse();
        assertThat(eff.tokensPerMinute()).isEqualTo(750_000);
    }

    @Test
    void noOverrideMetadata_returnsNone() {
        stubWorkspace("t1", Map.of("priority-tier", "premium")); // unrelated key
        assertThat(resolver(5).resolve("t1")).isEqualTo(EffectiveRateLimit.NONE);
    }

    @Test
    void nullMetadata_returnsNone() {
        stubWorkspace("t1", null);
        assertThat(resolver(5).resolve("t1")).isEqualTo(EffectiveRateLimit.NONE);
    }

    @Test
    void unknownWorkspace_returnsNone() {
        when(repo.findById("ghost")).thenReturn(Optional.empty());
        assertThat(resolver(5).resolve("ghost")).isEqualTo(EffectiveRateLimit.NONE);
    }

    @Test
    void nullOrBlankWorkspaceId_returnsNoneWithoutRepoHit() {
        assertThat(resolver(5).resolve(null)).isEqualTo(EffectiveRateLimit.NONE);
        assertThat(resolver(5).resolve("  ")).isEqualTo(EffectiveRateLimit.NONE);
        verify(repo, times(0)).findById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonNumericValue_fallsBackToNone() {
        stubWorkspace("t1", Map.of("rate-limit.requests-per-minute", "lots"));
        assertThat(resolver(5).resolve("t1")).isEqualTo(EffectiveRateLimit.NONE);
    }

    @Test
    void zeroOrNegativeValue_fallsBackToNone() {
        stubWorkspace("t1", Map.of(
                "rate-limit.requests-per-minute", 0,
                "rate-limit.tokens-per-minute", -100));
        assertThat(resolver(5).resolve("t1")).isEqualTo(EffectiveRateLimit.NONE);
    }

    @Test
    void resultIsCachedWithinTtl() {
        stubWorkspace("t1", Map.of("rate-limit.requests-per-minute", 300));
        MetadataWorkspaceRateLimitResolver r = resolver(60);
        r.resolve("t1");
        r.resolve("t1");
        r.resolve("t1");
        verify(repo, times(1)).findById("t1"); // three resolves, one DB hit
    }

    @Test
    void cacheExpiresAfterTtl() throws InterruptedException {
        stubWorkspace("t1", Map.of("rate-limit.requests-per-minute", 300));
        MetadataWorkspaceRateLimitResolver r = resolver(0); // 0s TTL ⇒ every resolve re-reads
        r.resolve("t1");
        Thread.sleep(2);
        r.resolve("t1");
        verify(repo, times(2)).findById("t1");
    }
}