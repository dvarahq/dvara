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
import com.dvarahq.core.guardrail.VendorTokenCountSelector;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.policy.guardrail.TiktokenEstimator;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
            ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
            when(dispatcher.countInputTokens(any())).thenReturn(OptionalInt.of(9_999));
            return dispatcher;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PicksWorkspaceA {
        @Bean
        VendorTokenCountSelector onlyWorkspaceA() {
            return request -> "ws-a".equals(request.getMetadata().get("workspace_id"));
        }
    }

    private static ChatRequest request(String workspace) {
        return ChatRequest.builder().model("claude-haiku-4-5").metadata(Map.of("workspace_id", workspace))
                .messages(List.of(MultimodalMessage.user("hello there"))).build();
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

    @Test
    void falseIsOff() {
        runner.withPropertyValues("dvara.llm-gateway.token-count.vendor.enabled=false")
                .run(ctx -> assertThat(ctx.getBean(TokenEstimator.class)).isInstanceOf(TiktokenEstimator.class));
    }

    @Test
    void selectedAsksTheVendorOnlyForTheRequestsASelectorPicks() {
        runner.withUserConfiguration(PicksWorkspaceA.class)
                .withPropertyValues("dvara.llm-gateway.token-count.vendor.enabled=selected")
                .run(ctx -> {
                    TokenEstimator estimator = ctx.getBean(TokenEstimator.class);
                    assertThat(estimator.estimateTokens(request("ws-a"))).isEqualTo(9_999);
                    assertThat(estimator.estimateTokens(request("ws-b")))
                            .isEqualTo(new TiktokenEstimator().estimateTokens(request("ws-b")));
                });
    }

    @Test
    void selectedWithNoSelectorCountsEverythingLocally() {
        runner.withPropertyValues("dvara.llm-gateway.token-count.vendor.enabled=selected")
                .run(ctx -> assertThat(ctx.getBean(TokenEstimator.class).estimateTokens(request("ws-a")))
                        .isEqualTo(new TiktokenEstimator().estimateTokens(request("ws-a"))));
    }

    @Test
    void aValueItDoesNotKnowStopsTheStart() {
        runner.withPropertyValues("dvara.llm-gateway.token-count.vendor.enabled=sometimes")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .hasRootCauseMessage("dvara.llm-gateway.token-count.vendor.enabled is 'sometimes'; "
                                + "it takes false, true or selected"));
    }
}
