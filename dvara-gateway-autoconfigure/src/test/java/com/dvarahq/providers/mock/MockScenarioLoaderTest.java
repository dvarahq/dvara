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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockScenarioLoaderTest {

    private ChatRequest req(String model, String userText) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user(userText)))
                .temperature(0.7)
                .build();
    }

    // ---------------------------------------------------------------------
    // loadAll — directory scanning
    // ---------------------------------------------------------------------

    @Test
    void loadAll_nullDir_returnsEmpty() {
        assertThat(MockScenarioLoader.loadAll(null)).isEmpty();
    }

    @Test
    void loadAll_nonExistentDir_returnsEmpty(@TempDir Path tmp) {
        assertThat(MockScenarioLoader.loadAll(tmp.resolve("does-not-exist"))).isEmpty();
    }

    @Test
    void loadAll_emptyDir_returnsEmpty(@TempDir Path tmp) {
        assertThat(MockScenarioLoader.loadAll(tmp)).isEmpty();
    }

    @Test
    void loadAll_ignoresNonGroovyFiles(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("readme.txt"), "not a scenario");
        Files.writeString(tmp.resolve("config.yml"), "not a scenario");
        writeScenario(tmp, "one.groovy", "'scenario-one'",
                "{ request -> request.model == 'mock/one' }",
                "{ request -> 'one response' }");

        List<ScenarioMatcher> scenarios = MockScenarioLoader.loadAll(tmp);

        assertThat(scenarios).hasSize(1);
        assertThat(scenarios.get(0).name()).isEqualTo("scenario-one");
    }

    @Test
    void loadAll_sortsByFilenameLexicographically(@TempDir Path tmp) throws IOException {
        writeScenario(tmp, "02-second.groovy", "'second'", "{ request -> true }", "{ request -> 'b' }");
        writeScenario(tmp, "01-first.groovy", "'first'", "{ request -> true }", "{ request -> 'a' }");
        writeScenario(tmp, "03-third.groovy", "'third'", "{ request -> true }", "{ request -> 'c' }");

        List<ScenarioMatcher> scenarios = MockScenarioLoader.loadAll(tmp);

        assertThat(scenarios).extracting(ScenarioMatcher::name)
                .containsExactly("first", "second", "third");
    }

    // ---------------------------------------------------------------------
    // loadOne — single file parsing
    // ---------------------------------------------------------------------

    @Test
    void loadOne_basicScenario_matchesAndResponds(@TempDir Path tmp) throws IOException {
        Path file = writeScenario(tmp, "billing.groovy", "'billing-question'",
                "{ request -> request.model == 'mock/billing' }",
                "{ request -> 'Your balance is $42' }");

        ScenarioMatcher matcher = MockScenarioLoader.loadOne(file);

        assertThat(matcher.name()).isEqualTo("billing-question");
        assertThat(matcher.matches(req("mock/billing", "hi"))).isTrue();
        assertThat(matcher.matches(req("mock/other", "hi"))).isFalse();
        assertThat(matcher.respond(req("mock/billing", "hi"))).isEqualTo("Your balance is $42");
    }

    @Test
    void loadOne_scenarioUsesRequestFields_inRespondClosure(@TempDir Path tmp) throws IOException {
        Path file = writeScenario(tmp, "echo.groovy", "'echo-model'",
                "{ request -> request.model.startsWith('mock/') }",
                "{ request -> 'You sent: ' + request.model }");

        ScenarioMatcher matcher = MockScenarioLoader.loadOne(file);

        assertThat(matcher.respond(req("mock/xyz", "hi"))).isEqualTo("You sent: mock/xyz");
    }

    @Test
    void loadOne_missingNameUsesFilenameWithoutExtension(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("fallback-name.groovy");
        Files.writeString(file,
                "when = { request -> true }\n" +
                "respond = { request -> 'ok' }\n");

        ScenarioMatcher matcher = MockScenarioLoader.loadOne(file);

        assertThat(matcher.name()).isEqualTo("fallback-name");
    }

    @Test
    void loadOne_blankNameUsesFilenameFallback(@TempDir Path tmp) throws IOException {
        Path file = writeScenario(tmp, "my-scenario.groovy", "'   '",
                "{ request -> true }",
                "{ request -> 'ok' }");

        ScenarioMatcher matcher = MockScenarioLoader.loadOne(file);

        assertThat(matcher.name()).isEqualTo("my-scenario");
    }

    // ---------------------------------------------------------------------
    // loadOne — validation and error handling
    // ---------------------------------------------------------------------

    @Test
    void loadOne_missingWhenClosure_throwsWithFilePath(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("no-when.groovy");
        Files.writeString(file,
                "name = 'x'\n" +
                "respond = { request -> 'ok' }\n");

        assertThatIllegalStateException()
                .isThrownBy(() -> MockScenarioLoader.loadOne(file))
                .withMessageContaining("no-when.groovy")
                .withMessageContaining("missing required 'when'");
    }

    @Test
    void loadOne_missingRespondClosure_throwsWithFilePath(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("no-respond.groovy");
        Files.writeString(file,
                "name = 'x'\n" +
                "when = { request -> true }\n");

        assertThatIllegalStateException()
                .isThrownBy(() -> MockScenarioLoader.loadOne(file))
                .withMessageContaining("no-respond.groovy")
                .withMessageContaining("missing required 'respond'");
    }

    @Test
    void loadOne_whenIsNotAClosure_throwsWithTypeInfo(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("bad-when.groovy");
        Files.writeString(file,
                "name = 'x'\n" +
                "when = 'this is a string, not a closure'\n" +
                "respond = { request -> 'ok' }\n");

        assertThatIllegalStateException()
                .isThrownBy(() -> MockScenarioLoader.loadOne(file))
                .withMessageContaining("bad-when.groovy")
                .withMessageContaining("must be a closure");
    }

    @Test
    void loadOne_brokenGroovySyntax_throwsWithCompileError(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("broken.groovy");
        Files.writeString(file,
                "name = 'broken'\n" +
                "when = { request -> request.model == // unterminated comment");

        assertThatIllegalStateException()
                .isThrownBy(() -> MockScenarioLoader.loadOne(file))
                .withMessageContaining("broken.groovy")
                .withMessageContaining("failed to compile");
    }

    // ---------------------------------------------------------------------
    // runtime error wrapping
    // ---------------------------------------------------------------------

    @Test
    void scenarioPredicateThrowing_wrappedAsProviderError(@TempDir Path tmp) throws IOException {
        Path file = writeScenario(tmp, "predicate-boom.groovy", "'boom'",
                "{ request -> throw new RuntimeException('predicate broke') }",
                "{ request -> 'never' }");

        ScenarioMatcher matcher = MockScenarioLoader.loadOne(file);

        assertThatThrownBy(() -> matcher.matches(req("mock/any", "hi")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("predicate-boom.groovy")
                .hasMessageContaining("predicate broke");
    }

    @Test
    void scenarioResponseThrowing_wrappedAsProviderError(@TempDir Path tmp) throws IOException {
        Path file = writeScenario(tmp, "response-boom.groovy", "'boom'",
                "{ request -> true }",
                "{ request -> throw new RuntimeException('response broke') }");

        ScenarioMatcher matcher = MockScenarioLoader.loadOne(file);

        assertThatThrownBy(() -> matcher.respond(req("mock/any", "hi")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("response-boom.groovy")
                .hasMessageContaining("response broke");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /**
     * Writes a scenario file with all three top-level bindings. {@code nameExpr}
     * is a Groovy expression (e.g. {@code "'my-scenario'"}); {@code whenClosure}
     * and {@code respondClosure} are closure literals.
     */
    private Path writeScenario(Path dir, String filename, String nameExpr,
                               String whenClosure, String respondClosure) throws IOException {
        Path file = dir.resolve(filename);
        Files.writeString(file,
                "name = " + nameExpr + "\n" +
                "when = " + whenClosure + "\n" +
                "respond = " + respondClosure + "\n");
        return file;
    }
}