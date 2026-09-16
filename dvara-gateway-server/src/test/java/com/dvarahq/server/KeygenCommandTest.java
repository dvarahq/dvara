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
package com.dvarahq.server;

import com.dvarahq.core.apikey.ApiKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class KeygenCommandTest {

    private static final Pattern KEY = Pattern.compile("(gw_[0-9a-f]{40})");
    private static final Pattern HASH = Pattern.compile("key_hash: sha256:([0-9a-f]{64})");

    @Test
    void mintsAKeyAndTheFingerprintThatMatchesIt() {
        Out o = run("--generate-key");

        Matcher key = KEY.matcher(o.out);
        Matcher hash = HASH.matcher(o.out);
        assertThat(key.find()).as("the key is printed").isTrue();
        assertThat(hash.find()).as("the fingerprint is printed").isTrue();

        assertThat(hash.group(1))
                .as("the pair must match, or the operator configures a key that can never authenticate")
                .isEqualTo(ApiKeyGenerator.hash(key.group(1)));
        assertThat(o.code).isZero();
    }

    @Test
    void twoRunsDoNotProduceTheSameKey() {
        assertThat(firstKey(run("--generate-key").out))
                .isNotEqualTo(firstKey(run("--generate-key").out));
    }

    @Test
    void hashKeyFingerprintsAKeyYouAlreadyHoldAndDoesNotEchoIt() {
        String existing = ApiKeyGenerator.generatePlaintext();
        Out o = runWithStdin(existing + "\n", "--hash-key", "-");

        assertThat(o.out).as("the fingerprint is that of the key given")
                .contains("key_hash: sha256:" + ApiKeyGenerator.hash(existing));
        assertThat(o.out).as("a key the caller already holds is not printed back")
                .doesNotContain(existing);
        assertThat(o.code).isZero();
    }

    @Test
    void theFingerprintIsWhatTheStoreWouldHaveComputed() {
        String plaintext = ApiKeyGenerator.generatePlaintext();
        assertThat(firstHash(runWithStdin(plaintext, "--hash-key").out))
                .as("the command and the gateway must agree, or nothing authenticates")
                .isEqualTo(ApiKeyGenerator.hash(plaintext));
    }

    @Test
    void anUnknownScopeIsRefusedHereRatherThanNarrowingTheKeySilently() {
        Out o = run("--generate-key", "--scopes", "completion:write");

        assertThat(o.code).isEqualTo(2);
        assertThat(o.err).contains("Unknown scope 'completion:write'").contains("completions:write");
        assertThat(o.out).as("nothing is printed for a key that would have been wrong").isEmpty();
    }

    @Test
    void namesAndScopesAppearInTheBlockToPaste() {
        Out o = run("--generate-key", "--name", "ci", "--workspace", "acme",
                "--scopes", "completions:write,models:read");

        assertThat(o.out).contains("name: \"ci\"").contains("workspace: \"acme\"")
                .contains("scopes: [completions:write, models:read]");
    }

    /** The printed block parses as YAML and gives back the name and workspace exactly as typed. */
    @Test
    void aNameThatIsNotPlainYamlStillReadsBackAsTyped() throws Exception {
        Out o = run("--generate-key", "--name", "ci: nightly", "--workspace", "team #blue \"a\\b\"");

        JsonNode entry = new ObjectMapper(new YAMLFactory())
                .readTree(o.out.substring(o.out.indexOf("api_keys:")))
                .get("api_keys").get(0);
        assertThat(entry.get("name").asText()).isEqualTo("ci: nightly");
        assertThat(entry.get("workspace").asText()).isEqualTo("team #blue \"a\\b\"");
    }

    @Test
    void aKeyGivenAsAnArgumentStillWorksForOneReleaseWithAWarningAndIsNotEchoed() {
        String key = ApiKeyGenerator.generatePlaintext();
        Out o = run("--hash-key", key);

        assertThat(o.code).as("the argument form still succeeds").isZero();
        assertThat(o.out).contains("key_hash: sha256:" + ApiKeyGenerator.hash(key));
        assertThat(o.err).as("the warning names what was exposed, what to do, and that the form is going")
                .contains("shell history").contains("process list").contains("mint a replacement")
                .contains("next release");
        assertThat(o.out + o.err).as("the key is not printed back").doesNotContain(key);
    }

    @Test
    void theEqualsFormOfAnArgumentIsAcceptedTheSameWay() {
        String key = ApiKeyGenerator.generatePlaintext();
        Out o = run("--hash-key=" + key);

        assertThat(o.code).isZero();
        assertThat(o.out).contains("key_hash: sha256:" + ApiKeyGenerator.hash(key));
        assertThat(o.err).contains("next release");
        assertThat(o.out + o.err).doesNotContain(key);
    }

    /** Standard input, a file and an argument give the same fingerprint for the same key. */
    @Test
    void theThreeFormsProduceTheSameFingerprint(@TempDir Path dir) throws Exception {
        String key = ApiKeyGenerator.generatePlaintext();
        Path file = dir.resolve("key.txt");
        Files.writeString(file, key + "\n");

        assertThat(firstHash(runWithStdin(key + "\n", "--hash-key", "-").out))
                .as("the trailing newline in the file is not part of the key")
                .isEqualTo(firstHash(run("--hash-key-file", file.toString()).out))
                .isEqualTo(firstHash(run("--hash-key", key).out))
                .isEqualTo(ApiKeyGenerator.hash(key));
    }

    @Test
    void aFileThatIsNotThereIsRefusedAndNamed(@TempDir Path dir) {
        Out o = run("--hash-key-file", dir.resolve("absent.txt").toString());

        assertThat(o.code).isEqualTo(2);
        assertThat(o.err).contains("cannot read").contains("absent.txt");
        assertThat(o.out).as("nothing is printed for a key that was never read").isEmpty();
    }

    @Test
    void anEmptyFileIsRefusedRatherThanFingerprintingNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("key.txt");
        Files.writeString(file, "\n");

        Out o = run("--hash-key-file", file.toString());

        assertThat(o.code).isEqualTo(2);
        assertThat(o.err).contains("is empty");
        assertThat(o.out).isEmpty();
    }

    @Test
    void givingTheKeyBothWaysAtOnceIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("key.txt");
        Files.writeString(file, ApiKeyGenerator.generatePlaintext());

        Out o = runWithStdin("gw_a", "--hash-key", "-", "--hash-key-file", file.toString());

        assertThat(o.code).isEqualTo(2);
        assertThat(o.err).contains("not both");
        assertThat(o.out).isEmpty();
    }

    @Test
    void nothingOnStandardInputIsRefused() {
        Out o = runWithStdin("\n  \n", "--hash-key", "-");

        assertThat(o.code).isEqualTo(2);
        assertThat(o.err).contains("none arrived");
        assertThat(o.out).isEmpty();
    }

    @Test
    void bothCommandsAtOnceIsRefused() {
        Out o = run("--generate-key", "--hash-key", "gw_abc");
        assertThat(o.code).isEqualTo(2);
        assertThat(o.err).contains("not both");
    }

    @Test
    void onlyTheseArgumentsAreClaimed() {
        assertThat(KeygenCommand.handles(new String[]{"--server.port=9090"})).isFalse();
        assertThat(KeygenCommand.handles(new String[]{})).isFalse();
        assertThat(KeygenCommand.handles(new String[]{"--generate-key"})).isTrue();
        assertThat(KeygenCommand.handles(new String[]{"--hash-key", "gw_a"})).isTrue();
        assertThat(KeygenCommand.handles(new String[]{"--hash-key=gw_a"})).isTrue();
        assertThat(KeygenCommand.handles(new String[]{"--hash-key-file", "/tmp/k"})).isTrue();
        assertThat(KeygenCommand.handles(new String[]{"--hash-key-file=/tmp/k"})).isTrue();
    }

    private static String firstKey(String out) {
        Matcher m = KEY.matcher(out);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static String firstHash(String out) {
        Matcher m = HASH.matcher(out);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static Out run(String... args) {
        return runWithStdin("", args);
    }

    private static Out runWithStdin(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = KeygenCommand.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Out(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Out(int code, String out, String err) {}
}
