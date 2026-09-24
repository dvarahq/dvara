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
package com.dvarahq.autoconfigure.config.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.autoconfigure.config.yaml.repo.YamlConfigStore;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.routing.RouteRepository;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.workspace.WorkspaceRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The bootstrap loader is left out of a build configured from {@code gateway.yaml}, where every
 * repository is read-only and a bootstrap file could not be applied.
 */
class BootstrapLoaderConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootstrapLoaderAutoConfiguration.class))
            .withBean(WorkspaceRepository.class, () -> mock(WorkspaceRepository.class))
            .withBean(ApiKeyRepository.class, () -> mock(ApiKeyRepository.class))
            .withBean(RouteRepository.class, () -> mock(RouteRepository.class))
            .withBean(RoutingEngine.class, () -> new RoutingEngine((req, providers) -> providers.get(0), List.of()))
            .withBean(RoutingStrategyFactory.class, () -> mock(RoutingStrategyFactory.class));

    @Test
    void withoutTheYamlStore_theLoaderRegisters() {
        runner.run(context -> assertThat(context).hasSingleBean(BootstrapLoader.class));
    }

    /** Where configuration is stored but no request is routed, there is no routing engine to update. */
    @Test
    void withoutARoutingEngine_theLoaderStillRegisters() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BootstrapLoaderAutoConfiguration.class))
                .withBean(WorkspaceRepository.class, () -> mock(WorkspaceRepository.class))
                .withBean(ApiKeyRepository.class, () -> mock(ApiKeyRepository.class))
                .withBean(RouteRepository.class, () -> mock(RouteRepository.class))
                .run(context -> assertThat(context).hasSingleBean(BootstrapLoader.class));
    }

    @Test
    void onAFileConfiguredBuild_theLoaderIsAbsent() {
        runner.withBean(YamlConfigStore.class, () -> new YamlConfigStore(new GatewayYamlConfig()))
                .run(context -> assertThat(context).doesNotHaveBean(BootstrapLoader.class));
    }

    /** A bundle-serving pod has no YAML store either, so the missing-bean condition alone would let the loader in. */
    @Test
    void onABundleServingPod_theLoaderIsAbsent() {
        runner.withPropertyValues("dvara.data-plane.serve-from-bundle=true")
                .run(context -> assertThat(context).doesNotHaveBean(BootstrapLoader.class));
    }

    /** A bootstrap file set on a bundle-serving pod is warned about by name, not silently ignored, and the pod still starts. */
    @Test
    void onABundleServingPod_aBootstrapFileIsWarnedAbout_notRefused() {
        runner.withPropertyValues("dvara.data-plane.serve-from-bundle=true", "DVARA_BOOTSTRAP_FILE=/etc/dvara/gateway.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(BootstrapLoader.class);
                    assertThat(context.getBean(BootstrapLoaderAutoConfiguration.BundleServingBootstrapNote.class).warning())
                            .contains("DVARA_BOOTSTRAP_FILE").contains("signed bundle").contains("is not read");
                });
        runner.withPropertyValues("dvara.data-plane.serve-from-bundle=true", "GATEWAY_BOOTSTRAP_FILE=/etc/dvara/gateway.yaml")
                .run(context -> assertThat(context.getBean(BootstrapLoaderAutoConfiguration.BundleServingBootstrapNote.class).warning())
                        .contains("GATEWAY_BOOTSTRAP_FILE"));
        runner.withPropertyValues("dvara.data-plane.serve-from-bundle=true")
                .run(context -> assertThat(context.getBean(BootstrapLoaderAutoConfiguration.BundleServingBootstrapNote.class).warning()).isNull());
        runner.withPropertyValues("DVARA_BOOTSTRAP_FILE=/etc/dvara/gateway.yaml")
                .run(context -> assertThat(context).hasSingleBean(BootstrapLoader.class)
                        .doesNotHaveBean(BootstrapLoaderAutoConfiguration.BundleServingBootstrapNote.class));
    }

    /** The auto-configuration is not component-scanned, so the imports file must carry it. */
    @Test
    void bootImportsIt() throws Exception {
        String imports = new String(getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .readAllBytes());
        assertThat(imports).contains(BootstrapLoaderAutoConfiguration.class.getName());
    }
}
