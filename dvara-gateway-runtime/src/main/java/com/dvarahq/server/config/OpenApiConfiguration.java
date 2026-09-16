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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfiguration {

    /**
     * The version {@code /v3/api-docs} reports: Boot's build-info when the build generates it, else
     * the jar manifest's Implementation-Version, else "unknown". Never a literal, which would drift
     * from the real build.
     */
    static String apiVersion(BuildProperties buildProperties) {
        if (buildProperties != null && buildProperties.getVersion() != null && !buildProperties.getVersion().isBlank()) {
            return buildProperties.getVersion();
        }
        String manifest = OpenApiConfiguration.class.getPackage().getImplementationVersion();
        return manifest != null ? manifest : "unknown";
    }

    @Bean
    OpenAPI gatewayOpenAPI(ObjectProvider<BuildProperties> buildProperties) {
        return new OpenAPI()
                .info(new Info()
                        .title("DVARA — Data Plane API")
                        .version(apiVersion(buildProperties.getIfAvailable()))
                        .description("DVARA AI governance platform data plane — multi-provider LLM routing, workspace management, and API key lifecycle."));
    }

    @Bean
    GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/v1/**")
                .build();
    }
}
