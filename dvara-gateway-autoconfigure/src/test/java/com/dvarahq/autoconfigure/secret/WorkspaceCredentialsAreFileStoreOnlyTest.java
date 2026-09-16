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
package com.dvarahq.autoconfigure.secret;

import com.dvarahq.autoconfigure.config.yaml.repo.YamlConfigStoreAutoConfiguration;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.core.secret.WorkspaceCredentialSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-workspace credentials exist only where configuration comes from a file.
 *
 * <p>Where a database holds per-workspace credentials, a file-backed source registered alongside it
 * would answer first, from a map read at startup, and a credential rotated in the database would go
 * on being served from the file. So the condition is asserted rather than assumed.
 */
class WorkspaceCredentialsAreFileStoreOnlyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    YamlConfigStoreAutoConfiguration.class, SecretAutoConfiguration.class));

    @Test
    void withAJdbcClientOnTheClasspath_noSourceIsRegistered() {
        // The store's own condition. JdbcClient present means a database is possible, which is what
        // stands the whole file store down -- and the credential source with it.
        runner.run(context -> assertThat(context).doesNotHaveBean(WorkspaceCredentialSource.class));
    }

    @Test
    void andTheSecretProviderStillRegisters_unchanged() {
        // The provider must not depend on the source existing: with a database it is the platform
        // and environment fallback that a credential chain delegates to.
        runner.run(context -> assertThat(context).hasSingleBean(SecretProvider.class)
                .getBean(SecretProvider.class).isInstanceOf(PropertySecretProvider.class));
    }

    @Test
    void theIsolationRestsOnThisAnnotation_soPinIt() {
        // The two assertions above hold because the whole file store stands down when a JdbcClient
        // is on the classpath. Asserting the annotation names the fact the other tests depend on.
        var condition = YamlConfigStoreAutoConfiguration.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass.class);

        assertThat(condition).isNotNull();
        assertThat(condition.value())
                .contains("org.springframework.jdbc.core.simple.JdbcClient");
    }
}
