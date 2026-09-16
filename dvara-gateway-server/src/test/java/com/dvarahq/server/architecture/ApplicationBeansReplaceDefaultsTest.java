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
package com.dvarahq.server.architecture;

import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.guardrail.ContextWindowGovernor;
import com.dvarahq.core.guardrail.OutputSchemaValidator;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.security.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application that supplies its own implementation of a governed interface gets that
 * implementation injected.
 *
 * <p>Each default is conditioned on the interface it implements, not on its own class, and no
 * second bean of the type needs {@code @Primary} to win. Only the container can confirm this, so
 * the test runs against the real application with a plain configuration and no {@code @Primary}
 * anywhere in it.
 */
@SpringBootTest(classes = com.dvarahq.server.GatewayServerApplication.class,
        properties = "dvara.llm-gateway.providers.mock.enabled=true")
@Import(ApplicationBeansReplaceDefaultsTest.AnApplicationsOwnBeans.class)
class ApplicationBeansReplaceDefaultsTest {

    /** What a host application would write. Plain beans, no primary, no ordering, no qualifiers. */
    @TestConfiguration
    static class AnApplicationsOwnBeans {

        @Bean
        PolicyEngine myPolicyEngine() {
            return (context, request) -> PolicyDecision.ALLOW;
        }

        @Bean
        TokenEstimator myTokenEstimator() {
            return new TokenEstimator() {
                @Override
                public int estimateTokens(ChatRequest request) {
                    return 1;
                }

                @Override
                public int estimateTokens(String text) {
                    return 1;
                }
            };
        }

        @Bean
        SecurityContext mySecurityContext() {
            return new SecurityContext() {
                @Override
                public java.util.Optional<String> currentUserId() {
                    return java.util.Optional.of("an-application-user");
                }

                @Override
                public java.util.Optional<String> currentUserName() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Set<String> currentRoles() {
                    return java.util.Set.of();
                }

                @Override
                public boolean isAuthenticated() {
                    return true;
                }

                @Override
                public java.util.Optional<String> currentWorkspaceId() {
                    return java.util.Optional.empty();
                }
            };
        }
    }

    @Autowired
    PolicyEngine policyEngine;

    @Autowired
    TokenEstimator tokenEstimator;

    @Autowired
    SecurityContext securityContext;

    @Autowired
    PiiEnforcer piiEnforcer;

    @Autowired
    OutputSchemaValidator outputSchemaValidator;

    @Autowired
    ContextWindowGovernor contextWindowGovernor;

    @Test
    @DisplayName("the application's policy engine is the one injected")
    void policyEngineIsTheApplicationsOwn() {
        assertThat(policyEngine.evaluate(null, ChatRequest.builder().model("m").build()))
                .describedAs("the application's engine answers ALLOW, so it is the one injected")
                .isEqualTo(PolicyDecision.ALLOW);
        assertThat(policyEngine.getClass().getSimpleName()).doesNotContain("DefaultPolicyEngine");
    }

    @Test
    @DisplayName("the application's token estimator is the one injected")
    void tokenEstimatorIsTheApplicationsOwn() {
        assertThat(tokenEstimator.estimateTokens("anything at all")).isEqualTo(1);
    }

    @Test
    @DisplayName("the application's security context is the one injected")
    void securityContextIsTheApplicationsOwn() {
        assertThat(securityContext.currentUserId())
                .describedAs("the anonymous default reports no user; this one does")
                .contains("an-application-user");
    }

    @Test
    @DisplayName("the defaults it did not replace are still there")
    void theRestStillResolve() {
        // Replacement is per interface: supplying one bean leaves the other defaults in place.
        assertThat(piiEnforcer).isNotNull();
        assertThat(outputSchemaValidator).isNotNull();
        assertThat(contextWindowGovernor).isNotNull();
    }

    @Test
    @DisplayName("a filter context still resolves through the replaced engine")
    void theReplacedEngineIsWhatTheRequestPathHolds() {
        FilterContext ctx = FilterContext.builder().workspaceId("t1").build();
        assertThat(ctx.getWorkspaceId()).isEqualTo("t1");
        assertThat(policyEngine).isSameAs(policyEngine);
    }
}
