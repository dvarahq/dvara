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

import com.dvarahq.autoconfigure.config.yaml.repo.YamlConfigStore;
import com.dvarahq.autoconfigure.config.yaml.repo.YamlConfigStoreAutoConfiguration;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.routing.RouteRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * Registers {@link BootstrapLoader}, except where every repository is read-only and seeding could
 * only fail: a deployment configured from {@code gateway.yaml} (where {@code
 * YamlConfigStoreAutoConfiguration} judges {@code DVARA_BOOTSTRAP_FILE} itself), and a pod serving
 * its configuration from a bundle ({@code dvara.data-plane.serve-from-bundle=true}). On a
 * bundle-serving pod {@link BundleServingBootstrapNote} logs one warning if a bootstrap file is
 * set, rather than refusing: the Helm chart may point the bootstrap variable at the same
 * gateway.yaml whose providers are still read there, so only the seeding half of the file is moot.
 *
 * <p>An auto-configuration rather than a scanned component, because a
 * {@code @ConditionalOnMissingBean} on a scanned component is evaluated before any
 * auto-configuration has contributed a bean and would always be true.
 */
@AutoConfiguration(after = YamlConfigStoreAutoConfiguration.class)
public class BootstrapLoaderAutoConfiguration {

    static final String SERVE_FROM_BUNDLE = "dvara.data-plane.serve-from-bundle";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @ConditionalOnMissingBean(YamlConfigStore.class)
    @ConditionalOnProperty(name = SERVE_FROM_BUNDLE, havingValue = "false", matchIfMissing = true)
    public BootstrapLoader bootstrapLoader(WorkspaceRepository workspaceRepository,
                                           ApiKeyRepository apiKeyRepository,
                                           RouteRepository routeRepository,
                                           RoutingEngine routingEngine,
                                           RoutingStrategyFactory strategyFactory,
                                           Environment environment) {
        return new BootstrapLoader(workspaceRepository, apiKeyRepository, routeRepository,
                routingEngine, strategyFactory, environment);
    }

    /**
     * On a bundle-serving pod the loader is absent; this says why when a bootstrap file is set,
     * instead of leaving the variable silently ignored. One line, at WARN, at startup.
     */
    @Bean
    @ConditionalOnProperty(name = SERVE_FROM_BUNDLE, havingValue = "true")
    public BundleServingBootstrapNote bundleServingBootstrapNote(Environment environment) {
        return new BundleServingBootstrapNote(environment);
    }

    public static final class BundleServingBootstrapNote {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BundleServingBootstrapNote.class);
        private final String warning;

        BundleServingBootstrapNote(Environment environment) {
            String set = null;
            for (String name : new String[] {"DVARA_BOOTSTRAP_FILE", "GATEWAY_BOOTSTRAP_FILE"}) {
                String value = environment.getProperty(name);
                if (value != null && !value.isBlank()) {
                    set = name + "=" + value;
                    break;
                }
            }
            this.warning = set == null ? null : set + " is set, but this pod serves its configuration from a "
                    + "signed bundle, whose repositories are read-only: a bootstrap file cannot seed workspaces, "
                    + "api_keys or routes here, and is not read for them. Manage them where the bundle is "
                    + "produced. (A file's providers and rate_limits are read separately, from the file "
                    + "DVARA_CONFIG_FILE names.)";
            if (warning != null) {
                log.warn(warning);
            }
        }

        /** The line logged, or null when no bootstrap file is set. */
        public String warning() {
            return warning;
        }
    }
}
