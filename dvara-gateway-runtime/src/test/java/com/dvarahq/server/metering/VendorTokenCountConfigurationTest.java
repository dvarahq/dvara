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
import com.dvarahq.policy.guardrail.TiktokenEstimator;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The vendor-count mode is off unless asked for, and then wraps the estimator the application registered. */
class VendorTokenCountConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class Host {
        @Bean
        TokenEstimator tokenEstimator() {
            return new TiktokenEstimator();
        }

        @Bean
        ProviderDispatcher dispatcher() {
            return mock(ProviderDispatcher.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Host.class, VendorTokenCountConfiguration.class);

    @Test
    void offByDefault() {
        runner.run(ctx -> assertThat(ctx.getBean(TokenEstimator.class)).isInstanceOf(TiktokenEstimator.class));
    }

    @Test
    void onItWrapsTheRegisteredEstimator() {
        runner.withPropertyValues("dvara.llm-gateway.token-count.vendor.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(TokenEstimator.class))
                        .isInstanceOf(VendorCountingTokenEstimator.class));
    }
}
