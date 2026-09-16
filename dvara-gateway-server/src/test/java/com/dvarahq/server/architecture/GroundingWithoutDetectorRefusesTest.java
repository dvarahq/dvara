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
import com.dvarahq.core.guardrail.GroundingDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With grounding enabled and no detector registered, the gateway refuses; it does not report the
 * answer grounded.
 *
 * <p>Grounding needs an embedding model. This build has none, so it registers no
 * {@code GroundingDetector}, and a workspace that asks for grounding is told the build cannot do it.
 *
 * <p>The non-streaming filter throws. The streaming engine cannot throw through an open SSE
 * response, so it declares the control unavailable before the upstream is read and records a
 * {@code GROUNDING_UNAVAILABLE} audit intent.
 */
@SpringBootTest(classes = com.dvarahq.server.GatewayServerApplication.class,
        properties = "dvara.llm-gateway.providers.mock.enabled=true")
class GroundingWithoutDetectorRefusesTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("no grounding detector is registered")
    void noDetectorIsRegistered() {
        assertThat(context.getBeanNamesForType(GroundingDetector.class))
                .describedAs("no bean of type GroundingDetector; the absence is what the filter and "
                        + "the streaming engine react to")
                .isEmpty();
    }

    @Test
    @DisplayName("the application starts without one")
    void theContextStartsAnyway() {
        assertThat(context.getBeanDefinitionCount())
                .describedAs("the filter and the streaming engine take the detector optionally, so "
                        + "the context starts without one")
                .isPositive();
    }

    @Test
    @DisplayName("with grounding off, the missing detector is not a refusal")
    void disabledWithoutADetectorPasses() {
        var filter = context.getBean(com.dvarahq.server.filter.GroundingDetectionFilter.class);
        var ctx = com.dvarahq.core.filter.FilterContext.builder().build();

        var request = com.dvarahq.core.model.ChatRequest.builder()
                .model("mock/echo")
                .metadata(java.util.Map.of("grounding.sources", java.util.List.of("a source document")))
                .build();
        var response = com.dvarahq.core.model.ChatResponse.builder().build();

        assertThat(filter.postDispatch(request, response, ctx))
                .describedAs("grounding is off by default, so the filter passes the response through; "
                        + "the missing detector matters only once the control is asked for")
                .isSameAs(response);
    }

    @Test
    @DisplayName("the refusal is a 403 capability error, not a 502")
    void theErrorCodeIsMapped() {
        var handler = context.getBean(com.dvarahq.server.web.GlobalExceptionHandler.class);
        var body = handler.handleGatewayException(
                new GatewayException("GROUNDING_UNAVAILABLE", "no detector in this build"),
                new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse());

        assertThat(body.getStatusCode().value())
                .describedAs("GROUNDING_UNAVAILABLE is mapped to 403; an unmapped code would fall "
                        + "through to a 502 provider_error, which a client would retry")
                .isEqualTo(403);
    }

    /**
     * The streaming refusal itself is exercised in
     * {@code GroundingUnavailableRefusesBeforeDeliveryTest} in {@code dvara-gateway-policy}, which
     * drives a real {@code GuardedSseIterator} under both delivery modes. This checks only that the
     * engine declares the control unavailable up front.
     */
    @Test
    @DisplayName("the streaming engine declares the control unavailable up front")
    void streamingDeclaresTheControlUnavailable() {
        var engine = context.getBean(com.dvarahq.core.enforcement.StreamingEnforcementEngine.class);

        var asksForGrounding = new com.dvarahq.core.enforcement.StreamingPosture(
                true, com.dvarahq.core.pii.PiiAction.LOG, java.util.Map.of(),
                true, com.dvarahq.core.guardrail.GuardrailAction.LOG, 0.7,
                true, com.dvarahq.core.guardrail.GuardrailAction.LOG,
                java.util.List.of("a source document"));

        assertThat(engine.unavailableControl(asksForGrounding))
                .describedAs("declared before the upstream is read: under Immediate delivery a "
                        + "refusal at the end of the stream would arrive after the response has "
                        + "already been relayed")
                .isPresent()
                .get()
                .extracting(com.dvarahq.core.enforcement.StreamingEnforcementEngine.UnavailableControl::code)
                .isEqualTo("GROUNDING_UNAVAILABLE");
    }
}
