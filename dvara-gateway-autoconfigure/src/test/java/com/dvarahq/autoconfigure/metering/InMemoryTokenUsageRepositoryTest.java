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
package com.dvarahq.autoconfigure.metering;

import com.dvarahq.core.metering.TokenUsageRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTokenUsageRepositoryTest {

    private static TokenUsageRecord record(String workspace, String apiKey, String model) {
        return TokenUsageRecord.builder()
                .id(workspace + "-" + apiKey + "-" + model)
                .workspaceId(workspace).apiKey(apiKey).model(model)
                .inputTokens(1).outputTokens(1).totalTokens(2)
                .timestamp(Instant.parse("2026-09-01T00:00:00Z"))
                .build();
    }

    private static InMemoryTokenUsageRepository twoWorkspaces() {
        var repository = new InMemoryTokenUsageRepository(100);
        repository.save(record("acme", "key-a", "gpt-4o"));
        repository.save(record("globex", "key-b", "mistral-large"));
        return repository;
    }

    @Test
    void aNullWorkspaceFindsNothingRatherThanEveryWorkspace() {
        assertThat(twoWorkspaces().findByWorkspaceId(null))
                .as("a caller whose workspace did not resolve must not read the whole install's usage")
                .isEmpty();
    }

    @Test
    void aNullKeyFindsNothing() {
        assertThat(twoWorkspaces().findByApiKey(null)).isEmpty();
    }

    @Test
    void aNullModelFindsNothing() {
        assertThat(twoWorkspaces().findByModel(null)).isEmpty();
    }

    @Test
    void aNamedWorkspaceKeyOrModelFindsOnlyItsOwnRecords() {
        var repository = twoWorkspaces();

        assertThat(repository.findByWorkspaceId("acme")).extracting(TokenUsageRecord::getWorkspaceId)
                .containsExactly("acme");
        assertThat(repository.findByApiKey("key-b")).extracting(TokenUsageRecord::getApiKey)
                .containsExactly("key-b");
        assertThat(repository.findByModel("gpt-4o")).extracting(TokenUsageRecord::getModel)
                .containsExactly("gpt-4o");
    }

    @Test
    void summarizeSumsTheCacheAndReasoningBreakdown() {
        var repository = new InMemoryTokenUsageRepository();
        java.time.Instant at = java.time.Instant.parse("2026-09-01T10:00:00Z");
        repository.save(TokenUsageRecord.builder().workspaceId("acme").model("gpt-4o")
                .inputTokens(100).outputTokens(40).totalTokens(140)
                .cachedInputTokens(80).cacheWriteTokens(10).reasoningTokens(30).timestamp(at).build());
        repository.save(TokenUsageRecord.builder().workspaceId("acme").model("gpt-4o")
                .inputTokens(50).outputTokens(20).totalTokens(70)
                .cachedInputTokens(5).cacheWriteTokens(1).reasoningTokens(2).timestamp(at).build());
        // A row stored before the breakdown existed reads as zero for each part.
        repository.save(new TokenUsageRecord("r3", "acme", "key", "gpt-4o", "openai",
                10, 10, 20, false, null, "MISS", at));

        var summary = repository.summarize("acme", null, null, null);

        assertThat(summary.getTotalCachedInputTokens()).isEqualTo(85);
        assertThat(summary.getTotalCacheWriteTokens()).isEqualTo(11);
        assertThat(summary.getTotalReasoningTokens()).isEqualTo(32);
        // The breakdown is part of the totals, never added to them.
        assertThat(summary.getTotalInputTokens()).isEqualTo(160);
        assertThat(summary.getTotalOutputTokens()).isEqualTo(70);
        assertThat(summary.getTotalTokens()).isEqualTo(230);
    }
}
