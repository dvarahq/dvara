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
package com.dvarahq.policy.streaming;

import org.slf4j.Logger;
import com.dvarahq.core.enforcement.StreamingEnforcementTelemetry;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs Immediate-mode cancellation scans away from the provider/client thread under a deadline. */
public final class StreamingCancellationFinalizer {

    private static final Logger log = LoggerFactory.getLogger(StreamingCancellationFinalizer.class);
    private static final long DEADLINE_SECONDS = 5;
    private static final ExecutorService TASKS = Executors.newVirtualThreadPerTaskExecutor();
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(r ->
                    Thread.ofPlatform().daemon().name("dvara-stream-finalization-deadline").unstarted(r));

    private StreamingCancellationFinalizer() {
    }

    public static void submit(String plane, String workspaceId, Runnable finalization,
                              StreamingEnforcementTelemetry telemetry) {
        submit(plane, workspaceId, finalization, telemetry, DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    /** Deadline-parameterised form, so a test need not wait the production five seconds. */
    static void submit(String plane, String workspaceId, Runnable finalization,
                       StreamingEnforcementTelemetry telemetry, long deadline, TimeUnit unit) {
        StreamingEnforcementTelemetry metrics = telemetry == null
                ? StreamingEnforcementTelemetry.NOOP : telemetry;
        // One cancellation is reported at most once. Without this, a TIMEOUT interrupts the task,
        // the interrupted task throws, and the catch below counts the same cancellation as FAILED.
        AtomicBoolean reported = new AtomicBoolean();
        try {
            Future<?> future = TASKS.submit(() -> {
                try {
                    finalization.run();
                } catch (RuntimeException e) {
                    if (reported.compareAndSet(false, true)) {
                        metrics.cancellationFinalizationIncomplete(plane, "FAILED");
                        log.warn("Could not finalize a cancelled {} stream for workspace {}; delivered "
                                + "text may be unrecorded: {}", plane, workspaceId, e.getMessage());
                    }
                }
            });
            DEADLINES.schedule(() -> {
                if (!future.isDone() && reported.compareAndSet(false, true)) {
                    metrics.cancellationFinalizationIncomplete(plane, "TIMEOUT");
                    log.warn("Cancelled {} stream finalization exceeded {} {} for workspace {}; "
                                    + "interruption requested, delivered text may be unrecorded",
                            plane, deadline, unit.name().toLowerCase(), workspaceId);
                    future.cancel(true);
                }
            }, deadline, unit);
        } catch (RejectedExecutionException e) {
            metrics.cancellationFinalizationIncomplete(plane, "REJECTED");
            log.warn("Could not schedule cancelled {} stream finalization for workspace {}; delivered "
                    + "text may be unrecorded: {}", plane, workspaceId, e.getMessage());
        }
    }
}
