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
package com.dvarahq.server.metrics;

import com.dvarahq.core.enforcement.StreamingEnforcementTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Prometheus-facing measurements required by the streaming enforcement contract. */
@Component
public final class MeteredStreamingEnforcementTelemetry implements StreamingEnforcementTelemetry {

    private final MeterRegistry registry;

    public MeteredStreamingEnforcementTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void bufferObserved(String plane, String mode, String bound, long amount, long limit) {
        DistributionSummary.builder("gateway_streaming_enforcement_buffer_utilization")
                .description("Fraction of a streaming enforcement buffer bound retained")
                .tag("plane", plane).tag("mode", mode).tag("bound", bound)
                .baseUnit("ratio").register(registry)
                .record(limit == 0 ? 0.0 : (double) amount / limit);
    }

    @Override
    public void overflow(String plane, String mode, String bound) {
        Counter.builder("gateway_streaming_enforcement_overflow_total")
                .description("Streaming enforcement buffers that reached a configured bound")
                .tag("plane", plane).tag("mode", mode).tag("bound", bound)
                .register(registry).increment();
    }

    @Override
    public void cancellationFinalizationIncomplete(String plane, String reason) {
        Counter.builder("gateway_streaming_enforcement_cancellation_incomplete_total")
                .description("Cancelled Immediate streams whose audit finalization did not complete")
                .tag("plane", plane).tag("reason", reason)
                .register(registry).increment();
    }
}
