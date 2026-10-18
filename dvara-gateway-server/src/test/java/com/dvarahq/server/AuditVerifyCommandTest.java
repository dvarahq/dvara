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

import com.dvarahq.autoconfigure.audit.FileAuditWriter;
import com.dvarahq.core.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --verify-audit}: an operator checks an audit file from the gateway jar, with no Java to write. A good
 * file exits 0, a changed line exits 1 and is named, and a missing secret or path exits 2 and says what is missing.
 */
class AuditVerifyCommandTest {

    private static final String SECRET = "test-secret-not-the-shipped-default";
    private static final Map<String, String> ENV = Map.of(AuditVerifyCommand.SECRET_VARIABLE, SECRET);

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        return AuditVerifyCommand.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private Path auditFile() {
        Path file = dir.resolve("audit.log");
        FileAuditWriter writer = new FileAuditWriter(file, SECRET);
        for (String type : List.of("POLICY_DENIED", "PII_DETECTED", "GATEWAY_RESPONSE")) {
            writer.write(new AuditEvent("evt-" + type, Instant.parse("2026-10-18T10:15:30Z"), "acme", type,
                    Map.of("status", 200)));
        }
        return file;
    }

    @Test
    void theSwitchIsRecognisedInBothForms_andNothingElseIs() {
        assertThat(AuditVerifyCommand.handles(new String[]{"--verify-audit", "a.log"})).isTrue();
        assertThat(AuditVerifyCommand.handles(new String[]{"--verify-audit=a.log"})).isTrue();
        assertThat(AuditVerifyCommand.handles(new String[]{"--server.port=8080"})).isFalse();
    }

    @Test
    void anUntouchedFileVerifies_andExitsZero() {
        Path file = auditFile();

        assertThat(run(ENV, "--verify-audit", file.toString())).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("valid").contains("3");
        assertThat(run(ENV, "--verify-audit=" + file)).as("the = form").isZero();
    }

    @Test
    void aChangedLineExitsOne_andIsNamed() throws Exception {
        Path file = auditFile();
        List<String> lines = Files.readAllLines(file);
        lines.set(1, lines.get(1).replace("PII_DETECTED", "PII_IGNORED"));
        Files.write(file, lines);

        assertThat(run(ENV, "--verify-audit", file.toString())).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("INVALID").contains("line 2");
    }

    @Test
    void noSecretExitsTwo_andNamesTheVariable() {
        assertThat(run(Map.of(), "--verify-audit", auditFile().toString())).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("DVARA_AUDIT_HMAC_SECRET");
    }

    @Test
    void noPathExitsTwo_andShowsTheForm() {
        assertThat(run(ENV, "--verify-audit")).isEqualTo(2);
        assertThat(run(ENV, "--verify-audit", "--server.port=8080")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("needs the path");
    }

    @Test
    void aFileThatIsNotThereExitsTwo() {
        assertThat(run(ENV, "--verify-audit", dir.resolve("missing.log").toString())).isEqualTo(2);
    }
}
