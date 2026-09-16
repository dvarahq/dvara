/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.dvarahq.server.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeteredStreamingEnforcementTelemetryTest {

    @Test
    void publishesBufferOverflowAndCancellationMeasurements() {
        var registry = new SimpleMeterRegistry();
        var telemetry = new MeteredStreamingEnforcementTelemetry(registry);

        telemetry.bufferObserved("LLM", "DEFERRED", "HELD_CHARS", 500, 1_000);
        telemetry.overflow("A2A", "IMMEDIATE", "HELD_CHARS");
        telemetry.cancellationFinalizationIncomplete("LLM", "TIMEOUT");

        assertThat(registry.get("gateway_streaming_enforcement_buffer_utilization")
                .tag("bound", "HELD_CHARS").summary().count()).isEqualTo(1);
        assertThat(registry.get("gateway_streaming_enforcement_buffer_utilization")
                .tag("bound", "HELD_CHARS").summary().totalAmount()).isEqualTo(0.5);
        assertThat(registry.get("gateway_streaming_enforcement_overflow_total")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway_streaming_enforcement_cancellation_incomplete_total")
                .counter().count()).isEqualTo(1);
    }
}
