/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.dvarahq.policy.streaming;

import org.junit.jupiter.api.Test;
import com.dvarahq.core.enforcement.StreamingEnforcementTelemetry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingCancellationFinalizerTest {

    private final List<String> reasons = new CopyOnWriteArrayList<>();
    private final StreamingEnforcementTelemetry telemetry = new StreamingEnforcementTelemetry() {
        @Override public void cancellationFinalizationIncomplete(String plane, String reason) {
            reasons.add(reason);
        }
    };

    @Test
    void aTimedOutFinalizationIsReportedOnceEvenThoughTheInterruptMakesItThrow() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        Runnable blocksUntilInterrupted = () -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new IllegalStateException("interrupted", e); // what a blocked detector does
            }
        };

        StreamingCancellationFinalizer.submit("LLM", "t1", blocksUntilInterrupted, telemetry,
                200, TimeUnit.MILLISECONDS);

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200); // let the task's catch block run, if it is going to
        assertThat(reasons).as("one cancellation, one report").containsExactly("TIMEOUT");
    }

    @Test
    void aFailingFinalizationIsReportedAsFailedOnce() throws Exception {
        StreamingCancellationFinalizer.submit("LLM", "t1",
                () -> { throw new IllegalStateException("audit sink down"); }, telemetry,
                5, TimeUnit.SECONDS);

        Thread.sleep(300);
        assertThat(reasons).containsExactly("FAILED");
    }

    @Test
    void aCompletedFinalizationReportsNothing() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        StreamingCancellationFinalizer.submit("LLM", "t1", ran::countDown, telemetry,
                200, TimeUnit.MILLISECONDS);

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(400); // past the deadline
        assertThat(reasons).isEmpty();
    }
}
