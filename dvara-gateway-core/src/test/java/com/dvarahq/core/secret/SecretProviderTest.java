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
package com.dvarahq.core.secret;

import com.dvarahq.core.exception.GatewayException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretProviderTest {

    @Test
    void requireSecret_presentKey_returnsValue() {
        SecretProvider provider = key -> Optional.of("the-secret");

        assertThat(provider.requireSecret("provider.openai.api-key")).isEqualTo("the-secret");
    }

    @Test
    void requireSecret_missingKey_throwsCredentialNotFound() {
        SecretProvider provider = key -> Optional.empty();

        assertThatThrownBy(() -> provider.requireSecret("provider.openai.api-key"))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> {
                    GatewayException ge = (GatewayException) ex;
                    assertThat(ge.getCode()).isEqualTo("CREDENTIAL_NOT_FOUND");
                    assertThat(ge.getMessage()).contains("provider.openai.api-key");
                });
    }

    @Test
    void getSecret_returnsOptionalEmpty_forUnknownKey() {
        SecretProvider provider = key -> Optional.empty();

        assertThat(provider.getSecret("unknown.key")).isEmpty();
    }

    @Test
    void getSecret_returnsValue_forKnownKey() {
        SecretProvider provider = key -> {
            if ("provider.anthropic.api-key".equals(key)) {
                return Optional.of("sk-ant-123");
            }
            return Optional.empty();
        };

        assertThat(provider.getSecret("provider.anthropic.api-key")).hasValue("sk-ant-123");
        assertThat(provider.getSecret("provider.openai.api-key")).isEmpty();
    }
}