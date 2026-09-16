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
package com.dvarahq.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

/** The API version is the build's, never a literal. */
class OpenApiConfigurationTest {

    @Test
    void buildInfoWins() {
        Properties p = new Properties();
        p.setProperty("version", "9.9.9");
        assertThat(OpenApiConfiguration.apiVersion(new BuildProperties(p))).isEqualTo("9.9.9");
    }

    @Test
    void aBlankBuildVersion_fallsThrough() {
        Properties p = new Properties();
        p.setProperty("version", "  ");
        assertThat(OpenApiConfiguration.apiVersion(new BuildProperties(p)))
                .isEqualTo(OpenApiConfiguration.apiVersion(null));
    }

    @Test
    void withoutBuildInfo_theManifestOrUnknown_neverALiteral() {
        String v = OpenApiConfiguration.apiVersion(null);
        String manifest = OpenApiConfiguration.class.getPackage().getImplementationVersion();
        assertThat(v).isEqualTo(manifest != null ? manifest : "unknown").isNotEqualTo("1.0.1");
    }

    @Test
    void theDeletedStatusPathIsNotMatched() {
        assertThat(new OpenApiConfiguration().publicApi().getPathsToMatch()).containsExactly("/v1/**");
    }
}
