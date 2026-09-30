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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.core.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every way of saving a workspace is refused by the file store, and each refusal names the call, so
 * a caller never believes it saved something the next restart would revert.
 */
class YamlWorkspaceRepositoryWritesTest {

    private final YamlWorkspaceRepository repository =
            new YamlWorkspaceRepository(new YamlConfigStore(new GatewayYamlConfig()));
    private final Workspace workspace = Workspace.builder().id("ws").name("Name").build();

    @Test
    void aSaveWithoutMetadataIsRefusedByName() {
        assertThatThrownBy(() -> repository.saveWithoutMetadata(workspace))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("WorkspaceRepository.saveWithoutMetadata");
    }

    @Test
    void aSaveIfUnchangedIsRefusedByName() {
        assertThatThrownBy(() -> repository.saveIfUnchanged(workspace, Instant.EPOCH))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("WorkspaceRepository.saveIfUnchanged");
    }
}
