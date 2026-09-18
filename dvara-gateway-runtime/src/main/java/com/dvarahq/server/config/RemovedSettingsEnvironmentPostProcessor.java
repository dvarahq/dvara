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

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses, before the context starts, a setting this build no longer has.
 *
 * <p>{@code dvara.llm-gateway.data-plane.require-api-key} once decided whether a {@code /v1}
 * request could omit its key; it was removed in 1.8.0 and a key is always required. A property that
 * binds to nothing is silently ignored by the binder, and for this one silence would be wrong in
 * both directions: an operator who set it to {@code false} relied on keyless callers, and must learn
 * at startup rather than from a wave of {@code 401}s that the gateway no longer serves them; one who
 * set it to {@code true} should learn the line is dead weight, but has lost nothing, so the gateway
 * starts.
 *
 * <p>Read through the {@link ConfigurableEnvironment}, so every property source is seen, and also
 * by the environment-variable name the setting was documented under,
 * {@code DVARA_LLM_GATEWAY_REQUIRE_API_KEY}. That name is not the relaxed-binding spelling of the
 * property — the shipped {@code application.yml} mapped it by hand — so relaxed binding alone would
 * miss exactly the spelling every deployment descriptor uses. Runs last among the environment
 * post-processors, after the configuration files have been loaded.
 */
public class RemovedSettingsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String REQUIRE_API_KEY = "dvara.llm-gateway.data-plane.require-api-key";
    static final String REQUIRE_API_KEY_ENV = "DVARA_LLM_GATEWAY_REQUIRE_API_KEY";

    private final Log log;

    public RemovedSettingsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(RemovedSettingsEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String name = REQUIRE_API_KEY;
        String value = environment.getProperty(REQUIRE_API_KEY);
        if (value == null || value.isBlank()) {
            name = REQUIRE_API_KEY_ENV;
            value = environment.getProperty(REQUIRE_API_KEY_ENV);
        }
        if (value == null || value.isBlank()) {
            return;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            log.info(name + " is set to true. The setting was removed in 1.8.0: an API key is"
                    + " always required, so the line does nothing and can be dropped.");
            return;
        }
        // Anything but a plain true is refused, not only the literal false: an operator who wrote
        // "no", "0" or "off" meant the same thing, and a gateway that started anyway would serve
        // nothing they expected it to.
        throw new IllegalStateException(name + " is set to '" + value + "'. The setting was"
                + " removed in 1.8.0 and keyless requests are no longer served: every request under /v1"
                + " must carry an API key. Remove the setting (it is also read as the environment"
                + " variable DVARA_LLM_GATEWAY_REQUIRE_API_KEY), mint a key for each caller with"
                + " --generate-key, and put the printed key_hash in gateway.yaml.");
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
