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

import com.dvarahq.core.exception.GatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A workspace with grounding switched on, in a build with no detector, is refused.
 *
 * <p>{@code GroundingWithoutDetectorRefusesTest} covers the default, where grounding is off and
 * the missing detector does not matter. This one switches the control on through the install-wide
 * property, which is why it needs its own context.
 *
 * <p>The streaming half is covered by {@code GroundingUnavailableRefusesBeforeDeliveryTest} in
 * {@code dvara-gateway-policy}.
 */
@SpringBootTest(classes = com.dvarahq.server.GatewayServerApplication.class,
        properties = {
                "dvara.llm-gateway.providers.mock.enabled=true",
                "dvara.llm-gateway.guardrail.grounding.enabled=true"
        })
class GroundingEnabledWithoutDetectorRefusesTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("the dvara.llm-gateway.guardrail.grounding.enabled property switches grounding on")
    void theInstallWidePropertyIsHonoured() {
        assertThat(context.getBean(com.dvarahq.core.guardrail.GroundingConfig.class).enabled())
                .describedAs("the property is bound to GroundingConfig; without this the refusal "
                        + "below could pass for the wrong reason")
                .isTrue();
    }

    @Test
    @DisplayName("an enabled workspace is refused through the real filter")
    void enabledWithoutADetectorRefuses() {
        var filter = context.getBean(com.dvarahq.server.filter.GroundingDetectionFilter.class);
        var ctx = com.dvarahq.core.filter.FilterContext.builder().build();

        var request = com.dvarahq.core.model.ChatRequest.builder()
                .model("mock/echo")
                .metadata(java.util.Map.of(
                        "grounding.sources", java.util.List.of("a source document")))
                .build();
        var response = com.dvarahq.core.model.ChatResponse.builder().build();

        assertThatThrownBy(() -> filter.postDispatch(request, response, ctx))
                .describedAs("a control this build cannot perform is refused rather than passed "
                        + "through as checked")
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("no grounding detector")
                .extracting(e -> ((GatewayException) e).getCode())
                .isEqualTo("GROUNDING_UNAVAILABLE");
    }

}
