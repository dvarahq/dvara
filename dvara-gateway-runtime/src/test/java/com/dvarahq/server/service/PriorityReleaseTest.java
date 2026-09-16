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
package com.dvarahq.server.service;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.routing.PriorityTier;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.server.TestProviders;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.server.metrics.GatewayMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A priority slot is released exactly when one was taken, and never otherwise.
 *
 * <p>{@code releasePriority} runs in a {@code finally}, so it runs on every path. A
 * {@link PriorityAdmissionController} decrements unconditionally, so releasing a slot that was never
 * acquired drifts its count negative and the limiter quietly stops limiting. A slot can look taken
 * in two ways: a tier on the context with no controller, or a controller with no tier because the
 * request was refused before admission.
 */
class PriorityReleaseTest {

    @Test
    @DisplayName("no controller: a tier on the context releases nothing, and does not throw")
    void noControllerReleasesNothing() {
        var service = serviceWith(null);
        FilterContext ctx = FilterContext.builder().workspaceId("t1").build();
        ctx.setPriorityTier(PriorityTier.STANDARD.name());

        assertThatCode(() -> service.releasePriority(ctx))
                .describedAs("releasePriority runs in a finally on every request; throwing here would "
                        + "replace the request's real outcome with a NullPointerException")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a controller and a tier: released once")
    void aTakenSlotIsReleased() {
        PriorityAdmissionController controller = mock(PriorityAdmissionController.class);
        var service = serviceWith(controller);
        FilterContext ctx = FilterContext.builder().workspaceId("t1").build();
        ctx.setPriorityTier(PriorityTier.PREMIUM.name());

        service.releasePriority(ctx);

        verify(controller).release(PriorityTier.PREMIUM);
    }

    @Test
    @DisplayName("a controller but no tier: nothing was admitted, so nothing is released")
    void anUntakenSlotIsNotReleased() {
        PriorityAdmissionController controller = mock(PriorityAdmissionController.class);
        var service = serviceWith(controller);

        service.releasePriority(FilterContext.builder().workspaceId("t1").build());

        verify(controller, never()).release(org.mockito.ArgumentMatchers.any());
    }

    private static ChatExecutionService serviceWith(PriorityAdmissionController controller) {
        return new ChatExecutionService(
                mock(ProviderDispatcher.class), mock(RequestPipeline.class),
                TestProviders.of(mock(ResponseCache.class)),
                mock(TokenUsageRepository.class), TestProviders.of(mock(WorkspaceUsageListener.class)),
                TestProviders.of(mock(CostCalculationService.class)),
                TestProviders.of(mock(CostEstimator.class)), mock(PiiEnforcer.class),
                mock(RateLimiter.class), mock(StreamingResponseEnforcer.class), mock(AuditWriter.class),
                TestProviders.of(controller), mock(GatewayMetrics.class),
                mock(TokenEstimator.class), TestProviders.of(mock(CallOutcomeListener.class)));
    }
}
