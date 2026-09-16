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
package com.dvarahq.providers.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a mock scenarios directory for filesystem changes and triggers a
 * reload when any {@code .groovy} file is created, modified, or deleted.
 *
 * <p>The watcher runs on a single daemon thread and debounces rapid
 * successive events (common from editors that write-and-rename or
 * write-truncate-write) by waiting up to 200 ms for more events before
 * rescanning the directory.
 *
 * <p>Reloads are "all-or-nothing" — the watcher rescans the entire directory
 * via {@link MockScenarioLoader#loadAll(Path)} on every change. If any file
 * fails to compile, the reload is abandoned and the current matcher list
 * stays active. An error is logged so operators can see the broken scenario
 * without losing the working ones.
 *
 * <p>Start the watcher with {@link #start()} and stop it with {@link #close()}.
 * {@link #close()} is idempotent and safe to call multiple times.
 */
public final class MockScenarioWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MockScenarioWatcher.class);
    private static final long DEBOUNCE_MS = 200L;

    private final Path scenariosDir;
    private final Consumer<List<ScenarioMatcher>> onReload;
    private final Thread thread;
    private volatile WatchService watchService;
    private volatile boolean running;
    private volatile MockMatcherTelemetry telemetry = MockMatcherTelemetry.NOOP;

    public MockScenarioWatcher(Path scenariosDir, Consumer<List<ScenarioMatcher>> onReload) {
        this.scenariosDir = scenariosDir;
        this.onReload = onReload;
        this.thread = new Thread(this::watchLoop, "mock-scenario-watcher");
        this.thread.setDaemon(true);
    }

    /**
     * Sets the telemetry hook for scenario reload events. Called by
     * {@code ProviderAutoConfiguration} after bean construction so the
     * real metered implementation replaces the NOOP default when one
     * is available. {@code null} is coerced to NOOP.
     */
    public void setTelemetry(MockMatcherTelemetry telemetry) {
        this.telemetry = telemetry != null ? telemetry : MockMatcherTelemetry.NOOP;
    }

    /**
     * Returns the scenarios directory this watcher is registered on, for a
     * caller that reads and writes scenario files, such as a scenario editor.
     */
    public Path scenariosDir() {
        return scenariosDir;
    }

    /**
     * Triggers an immediate rescan of the scenarios directory, bypassing the
     * filesystem watcher. A caller that has just written a scenario file uses
     * this so the change applies to the next request, without waiting for the
     * watcher's debounce window. Safe to call at any time,
     * whether the watcher is started or not — it just does a loadAll + onReload
     * inline on the caller's thread.
     */
    public void reloadNow() {
        triggerReload();
    }

    /** Starts the watcher thread. Idempotent — calling twice is a no-op. */
    public synchronized void start() {
        if (running) return;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            scenariosDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            log.error("Failed to start mock scenario watcher on {}: {}", scenariosDir, e.getMessage());
            return;
        }
        this.running = true;
        this.thread.start();
        log.info("Mock scenario watcher started on {}", scenariosDir);
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException e) {
            log.debug("Error closing mock scenario watch service: {}", e.getMessage());
        }
        thread.interrupt();
        log.info("Mock scenario watcher stopped");
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            // Debounce: drain events for up to DEBOUNCE_MS to coalesce editor save bursts.
            boolean hasRelevantChange = collectRelevantEvents(key);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEBOUNCE_MS);
            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) break;
                WatchKey additional;
                try {
                    additional = watchService.poll(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ClosedWatchServiceException e) {
                    return;
                }
                if (additional == null) break;
                hasRelevantChange |= collectRelevantEvents(additional);
                additional.reset();
            }
            key.reset();

            if (hasRelevantChange && running) {
                triggerReload();
            }
        }
    }

    private boolean collectRelevantEvents(WatchKey key) {
        boolean relevant = false;
        for (var event : key.pollEvents()) {
            Object context = event.context();
            if (context instanceof Path path && path.toString().endsWith(".groovy")) {
                relevant = true;
            }
        }
        return relevant;
    }

    private void triggerReload() {
        List<ScenarioMatcher> reloaded;
        try {
            reloaded = MockScenarioLoader.loadAll(scenariosDir);
            onReload.accept(reloaded);
        } catch (RuntimeException e) {
            log.error("Mock scenarios reload failed — keeping previous matcher list active. {}", e.getMessage(), e);
            return;
        }
        // Telemetry is auxiliary: a metrics/audit failure must not abort the
        // reload or mask the successful log line. Swallow and WARN.
        try {
            telemetry.scenariosReloaded(reloaded.size());
        } catch (RuntimeException e) {
            log.warn("Mock scenario reload telemetry failed — reload itself succeeded: {}", e.getMessage());
        }
        log.info("Mock scenarios reloaded from {} — {} scenario(s) active", scenariosDir, reloaded.size());
    }
}