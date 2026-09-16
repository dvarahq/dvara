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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This build has no budget enforcement and does not suggest that it does.
 *
 * <p>Absent: the {@code BudgetEnforcer} interface, a filter at order 200 calling it, the
 * {@code /v1/budget/estimate} endpoint and the {@code X-Budget-Remaining-*} headers. Each of those
 * would read, in the code and on the wire, like a cap that was checked and not exceeded.
 *
 * <p>Present: the ordering slot {@code FilterOrder.BUDGET_ENFORCEMENT}, so a filter that does
 * enforce a budget sits at the same point of the pipeline wherever it runs, and the error-code
 * mapping that renders a {@code BUDGET_CAP_*} refusal as a 402 rather than a 502.
 */
@SpringBootTest(classes = com.dvarahq.server.GatewayServerApplication.class,
        properties = "dvara.llm-gateway.providers.mock.enabled=true")
class NoBudgetSurfaceTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("no budget type is on the classpath of the assembled gateway")
    void theContractIsNotHere() {
        assertThat(classIsPresent("com.dvarahq.core.cost.BudgetEnforcer"))
                .describedAs("the budget contract is not on the classpath")
                .isFalse();
        assertThat(classIsPresent("com.dvarahq.core.cost.BudgetCheckResult")).isFalse();
        assertThat(classIsPresent("com.dvarahq.server.filter.BudgetEnforcementFilter")).isFalse();
        assertThat(classIsPresent("com.dvarahq.server.v1.BudgetEstimateController")).isFalse();
        assertThat(classIsPresent("com.dvarahq.server.v1.BudgetHeaders")).isFalse();
    }

    @Test
    @DisplayName("nothing maps /v1/budget/estimate")
    void theEndpointIsNotMapped() {
        // Looked up by name: the actuator registers its own RequestMappingHandlerMapping, so a
        // lookup by type is ambiguous.
        var mappings = context.getBean("requestMappingHandlerMapping",
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);

        assertThat(mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition() == null
                        ? java.util.stream.Stream.<String>empty()
                        : info.getPathPatternsCondition().getPatternValues().stream())
                .toList())
                .describedAs("no handler method maps /v1/budget/estimate")
                .doesNotContain("/v1/budget/estimate");
    }

    @Test
    @DisplayName("the BUDGET_ENFORCEMENT ordering slot is 200")
    void theSlotStays() {
        assertThat(com.dvarahq.core.filter.FilterOrder.BUDGET_ENFORCEMENT)
                .describedAs("the slot is part of the pipeline's shared ordering and is reserved "
                        + "whether or not a filter sits behind it")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a BUDGET_CAP_HARD refusal is mapped to 402, not 502")
    void theErrorCodesStayMapped() {
        var handler = context.getBean(com.dvarahq.server.web.GlobalExceptionHandler.class);
        var body = handler.handleGatewayException(
                new com.dvarahq.core.exception.GatewayException("BUDGET_CAP_HARD", "over the cap"),
                new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse());

        assertThat(body.getStatusCode().value())
                .describedAs("an unmapped code falls through the catch-all as a retryable 502 "
                        + "provider_error")
                .isEqualTo(402);
    }

    private static boolean classIsPresent(String name) {
        try {
            Class.forName(name, false, NoBudgetSurfaceTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
