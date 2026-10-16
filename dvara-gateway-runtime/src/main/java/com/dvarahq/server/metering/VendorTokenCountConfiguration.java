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
package com.dvarahq.server.metering;

import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.server.service.ProviderDispatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.OptionalInt;

/**
 * The vendor-count mode, off by default: {@code dvara.llm-gateway.token-count.vendor.enabled=true} makes every token
 * count of a request ask the provider that would serve it first (see {@link VendorCountingTokenEstimator}).
 *
 * <p>Wraps whichever {@link TokenEstimator} the application registered rather than replacing it, so the local count
 * underneath stays the one the build chose, and a host that registers its own keeps it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dvara.llm-gateway.token-count.vendor.enabled", havingValue = "true")
public class VendorTokenCountConfiguration {

    @Bean
    static BeanPostProcessor vendorCountingTokenEstimatorWrapper(ObjectProvider<ProviderDispatcher> dispatcher,
                                                                 Environment environment) {
        Duration timeout = environment.getProperty("dvara.llm-gateway.token-count.vendor.timeout", Duration.class,
                Duration.ofMillis(300));
        Duration ttl = environment.getProperty("dvara.llm-gateway.token-count.vendor.ttl", Duration.class,
                Duration.ofSeconds(60));
        int cacheSize = environment.getProperty("dvara.llm-gateway.token-count.vendor.cache-size", Integer.class,
                10_000);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof TokenEstimator local) || bean instanceof VendorCountingTokenEstimator) {
                    return bean;
                }
                return new VendorCountingTokenEstimator(local, request -> {
                    ProviderDispatcher d = dispatcher.getIfAvailable();
                    if (d == null) {
                        return OptionalInt.empty();
                    }
                    // On the counting thread: the provider's credential is the workspace's, as its call's would be.
                    try (com.dvarahq.providers.support.WorkspaceScope.Scope ignored =
                                 com.dvarahq.providers.support.WorkspaceScope.open(
                                         VendorCountingTokenEstimator.workspaceOf(request))) {
                        return d.countInputTokens(request);
                    }
                }, timeout, ttl, cacheSize);
            }
        };
    }
}
