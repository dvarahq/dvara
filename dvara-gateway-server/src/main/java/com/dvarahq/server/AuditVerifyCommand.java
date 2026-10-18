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

import com.dvarahq.autoconfigure.audit.FileAuditChainVerifier;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code --verify-audit <path>}: checks an audit file's HMAC chain and exits, instead of starting the gateway.
 *
 * <p>The verifier is a class in the gateway jar, and the jar is a Spring Boot fat jar, so {@code java -cp} cannot
 * reach it. This switch is the way an operator or auditor runs it:
 *
 * <pre>
 * DVARA_AUDIT_HMAC_SECRET=... java -jar dvara-gateway-server.jar --verify-audit /var/lib/dvara/audit.log
 * </pre>
 *
 * <p>Like the key commands, {@code main} runs it before {@code SpringApplication.run}: no context, no
 * configuration, no port. The secret comes from {@code DVARA_AUDIT_HMAC_SECRET} and never from an argument,
 * because arguments show in the process list. The output and exit codes are the verifier's: 0 when the chain
 * verified over at least one record, 1 when a record failed (the line is named), 2 when nothing was checked.
 */
final class AuditVerifyCommand {

    static final String SWITCH = "--verify-audit";
    static final String SECRET_VARIABLE = "DVARA_AUDIT_HMAC_SECRET";

    private AuditVerifyCommand() {}

    static boolean handles(String[] args) {
        for (String a : args) {
            if (SWITCH.equals(a) || a.startsWith(SWITCH + "=")) {
                return true;
            }
        }
        return false;
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        String path = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(SWITCH + "=")) {
                path = args[i].substring(SWITCH.length() + 1);
            } else if (SWITCH.equals(args[i])) {
                path = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[i + 1] : "";
            }
        }
        if (path == null || path.isBlank()) {
            err.println(SWITCH + " needs the path of the audit file, for example:"
                    + " java -jar <gateway jar> " + SWITCH + " /var/lib/dvara/audit.log");
            return 2;
        }
        String secret = env.get(SECRET_VARIABLE);
        if (secret == null || secret.isBlank()) {
            err.println(SECRET_VARIABLE + " is not set. Verifying needs the same secret that signed the chain;"
                    + " without it every line reads as tampered.");
            return 2;
        }
        return FileAuditChainVerifier.run(Path.of(path), secret, out, err);
    }
}
