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

import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.guardrail.ContextWindowGovernor;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailEnforcer;
import com.dvarahq.core.guardrail.OutputSchemaValidator;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.prompt.PromptTemplateResolver;
import com.dvarahq.core.ratelimit.WorkspaceRateLimitResolver;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.security.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which beans the assembled gateway registers for each governed interface.
 *
 * <p>A context starts happily with several implementations of one interface: {@code @Primary}
 * picks one at each injection point, the others stay registered, and every functional test passes
 * with whichever candidate won. So this pins the bean names per interface, measured against the
 * real application rather than a slice. Exactly one seam holds two beans, and the second test says
 * which.
 *
 * <p>A name that vanished is a default that was removed; a name that appeared is a second
 * implementation the container will not complain about. Update the map in the commit that changes
 * the wiring.
 */
@SpringBootTest(classes = com.dvarahq.server.GatewayServerApplication.class,
        properties = "dvara.llm-gateway.providers.mock.enabled=true")
class GovernedSeamBeanCountTest {

    @Autowired
    ApplicationContext context;

    /**
     * Interface to the beans the assembled gateway registers for it, in registration order.
     *
     * <p>One entry is a single implementation. An empty list is a seam this build leaves unfilled;
     * its consumers behave as if the feature is absent. Two entries is the composite-plus-layer
     * case noted below.
     */
    private static Map<Class<?>, List<String>> expected() {
        Map<Class<?>, List<String>> m = new LinkedHashMap<>();

        // --- one implementation ---
        m.put(PolicyEngine.class, List.of("defaultPolicyEngine"));
        m.put(PiiDetector.class, List.of("piiDetector"));
        m.put(PiiEnforcer.class, List.of("piiEnforcer"));
        m.put(GuardrailEnforcer.class, List.of("guardrailEnforcer"));
        m.put(OutputSchemaValidator.class, List.of("outputSchemaValidator"));
        m.put(ContextWindowGovernor.class, List.of("contextWindowGovernor"));
        m.put(WorkspaceRateLimitResolver.class, List.of("workspaceRateLimitResolver"));
        m.put(TokenEstimator.class, List.of("tokenEstimator"));
        m.put(PromptTemplateResolver.class, List.of("defaultPromptTemplateResolver"));
        m.put(SecurityContext.class, List.of("anonymousSecurityContext"));

        // A composite and one of its own layers, both of this type by design. The composite is
        // @Primary; a missing-bean condition on it would be satisfied by the layer and leave the
        // content filter as the entire guardrail.
        m.put(GuardrailDetector.class, List.of("contentFilterDetector", "guardrailDetector"));

        // One resolver, chosen by property rather than by @Primary: with resilience enabled it is
        // ResilienceAutoConfiguration's; with it disabled it is GatewayAutoConfiguration's
        // `disabledFallbackResolver`, which returns no candidates (see
        // ResilienceDisabledPerformsNoFallbackTest). Both are @ConditionalOnMissingBean on the
        // interface, so they never coexist and an application's own resolver stands both down.
        m.put(FallbackResolver.class, List.of("fallbackResolver"));

        // One bean; a second would be separated from it only by @Primary.
        m.put(com.dvarahq.core.resilience.ProviderHealthRegistry.class, List.of("providerHealthRegistry"));

        // --- no bean at all ---
        //
        // Nothing registers these and there is no stand-in. The consumers resolve an ObjectProvider
        // once at construction and guard the call: a build that cannot price a call books no cost
        // row, one that caches nothing misses every time, and one that does not admit by priority
        // never takes a slot.
        m.put(CostEstimator.class, List.of());
        m.put(CostCalculationService.class, List.of());
        m.put(ResponseCache.class, List.of());
        // PriorityAdmissionController is a seam even though nothing implements it: the execution
        // path releases the slot an admission filter took. WorkspacePriorityResolver is not a seam
        // in this build; nothing here asks which tier a workspace is.
        m.put(PriorityAdmissionController.class, List.of());

        // No detector: a workspace that asks for grounding is refused
        // (see GroundingWithoutDetectorRefusesTest).
        m.put(GroundingDetector.class, List.of());

        // Listeners are injected as Lists, so an empty list means nobody is listening, and a module
        // registering one joins the list rather than replacing a default.
        m.put(com.dvarahq.core.metering.WorkspaceUsageListener.class, List.of());
        m.put(com.dvarahq.core.metering.CallOutcomeListener.class, List.of());
        // One listener: guardrail blocking is implemented in this build, and the runtime bridges its
        // counters to GatewayMetrics. A second listener from an application would be called beside it.
        m.put(com.dvarahq.core.guardrail.GuardrailMetricsListener.class,
                List.of("guardrailMetricsListenerImpl"));

        // No classifier hook; the detector takes it optionally and checks isAvailable().
        m.put(com.dvarahq.core.guardrail.MlClassifierHook.class, List.of());

        // A batch submit does not travel the filter pipeline, so this gate is the one place a batch
        // can be refused before it is accepted. This build registers none, and the submit path
        // proceeds without a check.
        m.put(com.dvarahq.core.batch.BatchSubmitGate.class, List.of());

        return m;
    }

    @Test
    @DisplayName("each governed interface resolves to exactly the implementations recorded here")
    void theAssembledContextHoldsWhatIsRecorded() {
        Map<Class<?>, List<String>> actual = new LinkedHashMap<>();
        expected().keySet().forEach(seam ->
                actual.put(seam, List.of(context.getBeanNamesForType(seam))));

        assertThat(actual)
                .describedAs("the beans registered per governed interface; update this map in the "
                        + "commit that changes the wiring")
                .containsExactlyInAnyOrderEntriesOf(expected());
    }

    @Test
    @DisplayName("exactly one seam holds two implementations")
    void oneSeamHoldsMoreThanOneImplementation() {
        long duplicated = expected().keySet().stream()
                .filter(seam -> context.getBeanNamesForType(seam).length > 1)
                .count();

        assertThat(duplicated)
                .describedAs("governed interfaces holding more than one implementation; only "
                        + "GuardrailDetector does, a composite beside its own layer")
                .isEqualTo(1);
    }
}
