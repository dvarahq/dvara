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
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.autoconfigure.batch.InMemoryBatchJobRepository;
import com.dvarahq.core.secret.WorkspaceCredentialSource;
import com.dvarahq.autoconfigure.metering.InMemoryTokenUsageRepository;
import com.dvarahq.core.batch.BatchJobRepository;
import com.dvarahq.core.guardrail.OutputSchemaRepository;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.policy.PolicyRepository;
import com.dvarahq.core.prompt.PromptTemplateRepository;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.RouteRepository;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Serves configuration from {@code gateway.yaml} rather than from a database or a control plane.
 * The file is the source of truth for the life of the process.
 *
 * <h2>The conditions, and why neither is a property</h2>
 *
 * <p><b>No {@code JdbcClient} on the classpath.</b> This is a stronger statement than "no
 * datasource is configured": it says a database is impossible here, not merely unconfigured, so a
 * forgotten {@code spring.datasource.url} cannot silently switch where configuration comes from.
 * The condition names the third-party class whose absence is the actual fact, the same shape
 * {@code ActuatorAuthAutoConfiguration} uses.
 *
 * <p><b>Not serving from a signed bundle</b> ({@code dvara.data-plane.serve-from-bundle}). A module
 * that serves configuration from a bundle registers its own repositories, and this store stands
 * down declaratively rather than being overridden.
 *
 * <p>Between them there is no property to set: a build with no database and no control plane reads
 * the file. {@code java -jar} beside a {@code gateway.yaml}, no flags.
 *
 * <h2>Bean naming</h2>
 *
 * <p>Every file-backed bean here is prefixed {@code yaml…}, so none of them takes a name such as
 * {@code workspaceRepository} that another module's qualifier-based injection could pick up by
 * accident.
 */
@AutoConfiguration
@ConditionalOnMissingClass("org.springframework.jdbc.core.simple.JdbcClient")
@ConditionalOnProperty(name = "dvara.data-plane.serve-from-bundle", havingValue = "false",
        matchIfMissing = true)
public class YamlConfigStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigStoreAutoConfiguration.class);

    @Bean
    public YamlConfigStore yamlConfigStore(Environment environment) {
        // A separate bootstrap file cannot seed a read-only store: refuse now rather than warn per
        // entry later. The same file as the store reads is already applied, and is said to be.
        String alreadyServed = BootstrapFileOnReadOnlyStore.check(environment);
        if (alreadyServed != null) log.info(alreadyServed);
        GatewayYamlConfig config = GatewayYamlLoader.load(environment::getProperty).orElseGet(() -> {
            log.warn("No gateway.yaml found (looked at DVARA_CONFIG_FILE, then ./gateway.yaml). "
                    + "Starting with no workspaces, keys, routes or policies: every request under /v1 "
                    + "will be refused with 401 until a key is minted with --generate-key and its "
                    + "hash is in the file.");
            return new GatewayYamlConfig();
        });

        List<String> errors = GatewayYamlLoader.validate(config);
        if (!errors.isEmpty()) {
            // The file is the whole configuration, so a broken one is not a partial start. Naming
            // every error at once beats making the operator find them one restart at a time.
            throw new IllegalStateException("gateway.yaml is not valid:\n  - " + String.join("\n  - ", errors));
        }

        YamlConfigStore store = new YamlConfigStore(config);
        log.info("Configuration is being served from gateway.yaml: {} workspace(s), {} API key(s), "
                        + "{} route(s), {} policy(ies). It is read once at startup — edit the file and "
                        + "restart to change any of it.",
                store.workspaces().size(), store.keys().size(), store.routes().size(),
                store.policies().size());
        return store;
    }

    @Bean
    public WorkspaceRepository yamlWorkspaceRepository(YamlConfigStore store) {
        return new YamlWorkspaceRepository(store);
    }

    @Bean
    public ApiKeyRepository yamlApiKeyRepository(YamlConfigStore store) {
        return new YamlApiKeyRepository(store);
    }

    @Bean
    public RouteRepository yamlRouteRepository(YamlConfigStore store) {
        return new YamlRouteRepository(store);
    }

    @Bean
    public PolicyRepository yamlPolicyRepository(YamlConfigStore store) {
        return new YamlPolicyRepository(store);
    }

    @Bean
    public YamlRoutePublisher yamlRoutePublisher(YamlConfigStore store,
                                                 RoutingEngine routingEngine,
                                                 RoutingStrategyFactory strategyFactory) {
        return new YamlRoutePublisher(store, routingEngine, strategyFactory);
    }

    @Bean
    public PromptTemplateRepository yamlPromptTemplateRepository(YamlConfigStore store) {
        return new YamlPromptTemplateRepository(store);
    }

    @Bean
    public OutputSchemaRepository yamlOutputSchemaRepository(YamlConfigStore store) {
        return new YamlOutputSchemaRepository(store);
    }

    /**
     * The credentials each workspace brought, for the secret provider to prefer over the
     * installation-wide value.
     *
     * <p>Declared here rather than beside the secret provider: this class is
     * {@code @ConditionalOnMissingClass(JdbcClient)}, so a build with a database registers no source
     * at all, and per-workspace credentials there are a database concern with a lifecycle this file
     * cannot express. A method reference rather than a class, because the store already holds the
     * map and the seam is one method.
     */
    @Bean
    @ConditionalOnMissingBean(WorkspaceCredentialSource.class)
    public WorkspaceCredentialSource yamlWorkspaceCredentials(YamlConfigStore store) {
        return store::credentialFor;
    }

    // The beans below are not read out of the file. They hold or discard runtime state (usage
    // rows, batch tracking, canary comparisons) that has nowhere durable to go on a build with no
    // database. They are @ConditionalOnMissingBean, so anything supplying a real one wins, and
    // their names do not start with "yaml". Which repositories are file-backed is decided by
    // whether anything in this build reads them: output schemas and prompt templates have consumers
    // here (DefaultOutputSchemaValidator, DefaultPromptTemplateResolver); model pricing, budget
    // caps, semantic cache, golden prompts and prompt experiments do not, so a file entry for them
    // would configure nothing.

    @Bean
    @ConditionalOnMissingBean
    public TokenUsageRepository inMemoryTokenUsageRepository() {
        return new InMemoryTokenUsageRepository();
    }

    /**
     * Bounded and lost on restart, which the store says at startup. Without it the Batch endpoints
     * cannot poll or fetch results at all, so this is what makes them work with no database.
     */
    @Bean
    @ConditionalOnMissingBean
    public BatchJobRepository inMemoryBatchJobRepository() {
        return new InMemoryBatchJobRepository();
    }

    /** Discards canary comparisons: the split still routes, but nothing in this build stores or reads the numbers. */
    @Bean
    @ConditionalOnMissingBean
    public CanaryMetricsCollector uncollectedCanaryMetrics() {
        return new UncollectedRoutingMetrics.Canary();
    }

}