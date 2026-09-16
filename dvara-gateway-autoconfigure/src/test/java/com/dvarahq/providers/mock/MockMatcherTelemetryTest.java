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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that matcher fires and scenario reloads flow through the
 * {@link MockMatcherTelemetry} hook — {@link MockProvider} calls it on
 * every fired matcher and {@link MockScenarioWatcher#reloadNow()} calls
 * it on every successful reload.
 *
 * <p>Uses a capturing test double rather than Mockito because the semantics
 * being tested (ordering of calls, source enum) are clearer in a plain
 * data-collecting impl than in a mock with verify() matchers.
 */
class MockMatcherTelemetryTest {

    private static class RecordingTelemetry implements MockMatcherTelemetry {
        final CopyOnWriteArrayList<FiredEvent> fires = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<ChatRequest> fallthroughs = new CopyOnWriteArrayList<>();
        final AtomicInteger reloadCount = new AtomicInteger();
        final AtomicInteger lastReloadScenarioCount = new AtomicInteger(-1);

        @Override
        public void matcherFired(String scenarioName, Source source, ChatRequest request) {
            fires.add(new FiredEvent(scenarioName, source, request));
        }

        @Override
        public void matcherFellThrough(ChatRequest request) {
            fallthroughs.add(request);
        }

        @Override
        public void scenariosReloaded(int scenarioCount) {
            reloadCount.incrementAndGet();
            lastReloadScenarioCount.set(scenarioCount);
        }
    }

    private record FiredEvent(String scenario, MockMatcherTelemetry.Source source, ChatRequest request) {}

    /**
     * Test double that throws on every telemetry call, so the tests can check that
     * MockProvider and MockScenarioWatcher swallow telemetry exceptions
     * rather than letting them abort the request or reload.
     */
    private static class ThrowingTelemetry implements MockMatcherTelemetry {
        @Override
        public void matcherFired(String scenarioName, Source source, ChatRequest request) {
            throw new RuntimeException("simulated metrics backend failure");
        }

        @Override
        public void scenariosReloaded(int scenarioCount) {
            throw new RuntimeException("simulated audit writer failure");
        }
    }

    private ChatRequest req(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .temperature(0.7)
                .build();
    }

    private CompiledMatcher yamlMatcher(String name, String whenSource, String response) {
        return new CompiledMatcher(name, new MatcherEvaluator(whenSource), response);
    }

    private ScenarioMatcher fileScenario(String name, String whenSource, String respondText) {
        groovy.lang.Binding binding = new groovy.lang.Binding();
        new groovy.lang.GroovyShell(binding).evaluate(
                "when = " + whenSource + "\n" +
                "respond = { req -> '" + respondText + "' }\n");
        groovy.lang.Closure<?> whenClosure = (groovy.lang.Closure<?>) binding.getVariable("when");
        groovy.lang.Closure<?> respondClosure = (groovy.lang.Closure<?>) binding.getVariable("respond");
        return new ScenarioMatcher(name, "test:" + name, whenClosure, respondClosure);
    }

    // ---------------------------------------------------------------------
    // Default behavior — NOOP telemetry must be the default
    // ---------------------------------------------------------------------

    @Test
    void noopTelemetry_isDefault_andDoesNothing() {
        // The interface's NOOP default does not throw and is non-null.
        MockMatcherTelemetry.NOOP.matcherFired("anything", MockMatcherTelemetry.Source.FILE, req("mock/any"));
        MockMatcherTelemetry.NOOP.scenariosReloaded(42);
        assertThat(MockMatcherTelemetry.NOOP).isNotNull();
    }

    @Test
    void mockProvider_defaultTelemetry_isNoop() {
        // A freshly constructed MockProvider has a NOOP telemetry hook.
        // We cannot directly observe the field, so we verify behavioral
        // equivalence: calling chat() does not throw even without wiring.
        var provider = new MockProvider("default",
                List.of(yamlMatcher("x", "true", "ok")), 0, 0, 0.0);
        provider.chat(req("mock/any"));
        // If the NOOP was not installed as the default, the first resolve
        // would NPE on the null telemetry field. Passing this line proves
        // the NOOP default is wired correctly.
    }

    // ---------------------------------------------------------------------
    // MockProvider — fires on each match, with correct source label
    // ---------------------------------------------------------------------

    @Test
    void mockProvider_yamlMatcherFire_callsTelemetryWithYamlSource() {
        var telemetry = new RecordingTelemetry();
        var provider = new MockProvider("default",
                List.of(yamlMatcher("billing", "request.model == 'mock/billing'", "Your balance")),
                0, 0, 0.0);
        provider.setTelemetry(telemetry);

        provider.chat(req("mock/billing"));

        assertThat(telemetry.fires).hasSize(1);
        assertThat(telemetry.fires.get(0).scenario()).isEqualTo("billing");
        assertThat(telemetry.fires.get(0).source()).isEqualTo(MockMatcherTelemetry.Source.YAML);
    }

    @Test
    void mockProvider_firePassesRequestThroughToTelemetry() {
        var telemetry = new RecordingTelemetry();
        var provider = new MockProvider("default",
                List.of(yamlMatcher("x", "true", "ok")), 0, 0, 0.0);
        provider.setTelemetry(telemetry);
        ChatRequest submitted = req("mock/with-context");

        provider.chat(submitted);

        assertThat(telemetry.fires).hasSize(1);
        assertThat(telemetry.fires.get(0).request()).isNotNull();
        assertThat(telemetry.fires.get(0).request().getModel()).isEqualTo("mock/with-context");
    }

    @Test
    void mockProvider_fileScenarioFire_callsTelemetryWithFileSource() {
        var telemetry = new RecordingTelemetry();
        var provider = new MockProvider("default", List.of(), 0, 0, 0.0);
        provider.setTelemetry(telemetry);
        provider.replaceFileScenarios(List.of(
                fileScenario("echo", "{ req -> req.model == 'mock/echo' }", "echo text")));

        provider.chat(req("mock/echo"));

        assertThat(telemetry.fires).hasSize(1);
        assertThat(telemetry.fires.get(0).scenario()).isEqualTo("echo");
        assertThat(telemetry.fires.get(0).source()).isEqualTo(MockMatcherTelemetry.Source.FILE);
    }

    @Test
    void mockProvider_noMatcherFires_recordsFallthroughInsteadOfFire() {
        var telemetry = new RecordingTelemetry();
        var provider = new MockProvider("default",
                List.of(yamlMatcher("never", "false", "x")),
                0, 0, 0.0);
        provider.setTelemetry(telemetry);

        provider.chat(req("mock/anything"));

        assertThat(telemetry.fires).isEmpty();
        assertThat(telemetry.fallthroughs).hasSize(1);
        assertThat(telemetry.fallthroughs.get(0).getModel()).isEqualTo("mock/anything");
    }

    @Test
    void mockProvider_matcherFires_doesNotRecordFallthrough() {
        var telemetry = new RecordingTelemetry();
        var provider = new MockProvider("default",
                List.of(yamlMatcher("hit", "true", "ok")),
                0, 0, 0.0);
        provider.setTelemetry(telemetry);

        provider.chat(req("mock/any"));

        assertThat(telemetry.fires).hasSize(1);
        assertThat(telemetry.fallthroughs).isEmpty();
    }

    @Test
    void mockProvider_multipleMatchingCalls_eachEmitsOneFire() {
        var telemetry = new RecordingTelemetry();
        var provider = new MockProvider("default",
                List.of(yamlMatcher("hit", "true", "ok")),
                0, 0, 0.0);
        provider.setTelemetry(telemetry);

        for (int i = 0; i < 10; i++) {
            provider.chat(req("mock/" + i));
        }

        assertThat(telemetry.fires).hasSize(10);
    }

    @Test
    void mockProvider_setTelemetryNull_revertsToNoop() {
        var telemetry = new RecordingTelemetry();
        var provider = new MockProvider("default",
                List.of(yamlMatcher("hit", "true", "ok")),
                0, 0, 0.0);
        provider.setTelemetry(telemetry);
        provider.setTelemetry(null); // should coerce to NOOP, not crash

        provider.chat(req("mock/any"));

        assertThat(telemetry.fires).isEmpty();
    }

    // ---------------------------------------------------------------------
    // MockScenarioWatcher — reload telemetry
    // ---------------------------------------------------------------------

    @Test
    void watcher_reloadNow_callsScenariosReloaded_withCount(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        java.nio.file.Files.writeString(tmp.resolve("one.groovy"),
                "name = 'one'\nwhen = { r -> true }\nrespond = { r -> 'a' }\n");
        java.nio.file.Files.writeString(tmp.resolve("two.groovy"),
                "name = 'two'\nwhen = { r -> true }\nrespond = { r -> 'b' }\n");

        var telemetry = new RecordingTelemetry();
        var watcher = new MockScenarioWatcher(tmp, scenarios -> {});
        watcher.setTelemetry(telemetry);

        watcher.reloadNow();

        assertThat(telemetry.reloadCount).hasValue(1);
        assertThat(telemetry.lastReloadScenarioCount).hasValue(2);
    }

    @Test
    void watcher_reloadNow_brokenScenario_noTelemetryCall(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        // A broken scenario causes reload to fail — telemetry must NOT be called.
        java.nio.file.Files.writeString(tmp.resolve("broken.groovy"),
                "name = 'broken'\nwhen = { r -> // unterminated\n");

        var telemetry = new RecordingTelemetry();
        var watcher = new MockScenarioWatcher(tmp, scenarios -> {});
        watcher.setTelemetry(telemetry);

        watcher.reloadNow(); // does not throw — catches and logs

        assertThat(telemetry.reloadCount).hasValue(0);
    }

    @Test
    void watcher_setTelemetryNull_revertsToNoop(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws java.io.IOException {
        java.nio.file.Files.writeString(tmp.resolve("one.groovy"),
                "name = 'one'\nwhen = { r -> true }\nrespond = { r -> 'a' }\n");

        var telemetry = new RecordingTelemetry();
        var watcher = new MockScenarioWatcher(tmp, scenarios -> {});
        watcher.setTelemetry(telemetry);
        watcher.setTelemetry(null);

        watcher.reloadNow();

        assertThat(telemetry.reloadCount).hasValue(0);
    }

    // ---------------------------------------------------------------------
    // Failure isolation — a broken telemetry impl must not abort the
    // request flow or the reload flow
    // ---------------------------------------------------------------------

    @Test
    void mockProvider_telemetryThrows_chatStillReturnsMatcherResponse() {
        // A broken metrics/audit backend must never take down the data plane.
        // The request must still complete with the matcher's response, and
        // subsequent requests must continue working.
        var provider = new MockProvider("default",
                List.of(yamlMatcher("billing", "true", "from matcher")),
                0, 0, 0.0);
        provider.setTelemetry(new ThrowingTelemetry());

        for (int i = 0; i < 5; i++) {
            var response = provider.chat(req("mock/any"));
            assertThat(response.getChoices().get(0).getMessage().getContent().get(0).toString())
                    .contains("from matcher");
        }
    }

    @Test
    void watcher_telemetryThrows_reloadStillCompletesAndCallbackFires(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws java.io.IOException {
        java.nio.file.Files.writeString(tmp.resolve("one.groovy"),
                "name = 'one'\nwhen = { r -> true }\nrespond = { r -> 'a' }\n");

        CopyOnWriteArrayList<Integer> reloadCounts = new CopyOnWriteArrayList<>();
        var watcher = new MockScenarioWatcher(tmp, scenarios -> reloadCounts.add(scenarios.size()));
        watcher.setTelemetry(new ThrowingTelemetry());

        // Must not throw — the telemetry exception is swallowed and the
        // reload completes normally, including the onReload callback.
        watcher.reloadNow();

        assertThat(reloadCounts).containsExactly(1);
    }
}