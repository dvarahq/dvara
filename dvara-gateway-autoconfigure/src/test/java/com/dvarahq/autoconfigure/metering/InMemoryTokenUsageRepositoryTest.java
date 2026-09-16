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
}
