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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hot reload through {@link MockScenarioWatcher}, against the real filesystem and the real
 * {@link java.nio.file.WatchService}. The watch API is slow on macOS, where it is emulated by
 * polling, so the tests poll for the expected reload with a generous timeout.
 */
class MockScenarioWatcherTest {

    /** Longest the tests wait for a reload event. */
    private static final long WAIT_TIMEOUT_MS = 10_000L;

    /** Poll interval for the reload-observation loop. */
    private static final long POLL_INTERVAL_MS = 50L;

    /**
     * Pause after starting the watcher before touching files, so a modify event
     * cannot race the watcher's own registration.
     */
    private static final long WATCHER_SETTLE_MS = 300L;

    private MockScenarioWatcher watcher;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void watcher_reloadsWhenScenarioFileIsCreated(@TempDir Path tmp) throws IOException, InterruptedException {
        CopyOnWriteArrayList<List<ScenarioMatcher>> reloads = new CopyOnWriteArrayList<>();
        watcher = new MockScenarioWatcher(tmp, reloads::add);
        watcher.start();
        Thread.sleep(WATCHER_SETTLE_MS);

        writeScenarioFile(tmp, "created.groovy",
                "name = 'created-scenario'\n" +
                "when = { request -> true }\n" +
                "respond = { request -> 'from created' }\n");

        waitFor(() -> reloads.stream().anyMatch(list ->
                list.stream().map(ScenarioMatcher::name).anyMatch("created-scenario"::equals)));
    }

    @Test
    void watcher_reloadsWhenScenarioFileIsModified(@TempDir Path tmp) throws IOException, InterruptedException {
        // Pre-seed with an initial scenario before starting the watcher.
        Path file = tmp.resolve("seeded.groovy");
        Files.writeString(file,
                "name = 'initial-name'\n" +
                "when = { request -> true }\n" +
                "respond = { request -> 'initial response' }\n");

        CopyOnWriteArrayList<List<ScenarioMatcher>> reloads = new CopyOnWriteArrayList<>();
        watcher = new MockScenarioWatcher(tmp, reloads::add);
        watcher.start();
        Thread.sleep(WATCHER_SETTLE_MS);

        Files.writeString(file,
                "name = 'modified-name'\n" +
                "when = { request -> true }\n" +
                "respond = { request -> 'modified response' }\n");

        waitFor(() -> reloads.stream().anyMatch(list ->
                list.stream().map(ScenarioMatcher::name).anyMatch("modified-name"::equals)));
    }

    @Test
    void watcher_reloadsWhenScenarioFileIsDeleted(@TempDir Path tmp) throws IOException, InterruptedException {
        Path file = tmp.resolve("will-be-deleted.groovy");
        Files.writeString(file,
                "name = 'will-be-deleted'\n" +
                "when = { request -> true }\n" +
                "respond = { request -> 'ok' }\n");

        CopyOnWriteArrayList<List<ScenarioMatcher>> reloads = new CopyOnWriteArrayList<>();
        watcher = new MockScenarioWatcher(tmp, reloads::add);
        watcher.start();
        Thread.sleep(WATCHER_SETTLE_MS);

        Files.delete(file);

        waitFor(() -> !reloads.isEmpty() && reloads.get(reloads.size() - 1).isEmpty());
    }

    @Test
    void watcher_ignoresNonGroovyFileChanges(@TempDir Path tmp) throws IOException, InterruptedException {
        CopyOnWriteArrayList<List<ScenarioMatcher>> reloads = new CopyOnWriteArrayList<>();
        watcher = new MockScenarioWatcher(tmp, reloads::add);
        watcher.start();
        Thread.sleep(WATCHER_SETTLE_MS);

        Files.writeString(tmp.resolve("readme.txt"), "this is not a scenario");

        // Wait well past the debounce window; no reload should have happened.
        Thread.sleep(1500);
        assertThat(reloads).isEmpty();
    }

    @Test
    void watcher_brokenScenarioKeepsPreviousMatcherList(@TempDir Path tmp) throws IOException, InterruptedException {
        Path working = tmp.resolve("working.groovy");
        Files.writeString(working,
                "name = 'working'\n" +
                "when = { request -> true }\n" +
                "respond = { request -> 'ok' }\n");

        CopyOnWriteArrayList<List<ScenarioMatcher>> reloads = new CopyOnWriteArrayList<>();
        watcher = new MockScenarioWatcher(tmp, reloads::add);
        watcher.start();
        Thread.sleep(WATCHER_SETTLE_MS);

        Path broken = tmp.resolve("broken.groovy");
        Files.writeString(broken,
                "name = 'broken'\n" +
                "when = { request -> // unterminated");

        // Wait well past the debounce window. The reload fails, the watcher logs and skips,
        // and the callback never receives a list that includes the broken scenario.
        Thread.sleep(2000);

        assertThat(reloads).allSatisfy(list ->
                assertThat(list).extracting(ScenarioMatcher::name).doesNotContain("broken"));
    }

    @Test
    void watcher_closeIsIdempotent(@TempDir Path tmp) {
        watcher = new MockScenarioWatcher(tmp, reloads -> { });
        watcher.start();
        watcher.close();
        watcher.close(); // should not throw
        watcher.close(); // and neither should this
    }

    @Test
    void watcher_startWithoutPriorRegisterIsSafeToClose(@TempDir Path tmp) {
        watcher = new MockScenarioWatcher(tmp, reloads -> { });
        // start() is never called, so close() has nothing to do and must not throw.
        watcher.close();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError(
                "Condition not met within " + WAIT_TIMEOUT_MS + " ms — watcher did not observe the expected change");
    }

    private void writeScenarioFile(Path dir, String filename, String body) throws IOException {
        Files.writeString(dir.resolve(filename), body);
    }
}