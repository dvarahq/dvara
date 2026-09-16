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
import com.dvarahq.core.apikey.ApiKeyScope;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two commands that mint and fingerprint API keys, run instead of starting the gateway.
 *
 * <p>{@code main} runs them before {@code SpringApplication.run}, so no context is built, no
 * configuration is read and no port is bound: they work while the gateway is already running, and
 * Boot never sees {@code --generate-key} as a property.
 *
 * <p>The result goes to standard output as a ready-to-paste {@code api_keys} block, so
 * {@code | pbcopy} and {@code > key.txt} work and the key never lands in a log line.
 *
 * <p>A key to fingerprint comes on standard input ({@code --hash-key -}) or from a file
 * ({@code --hash-key-file <path>}), because an argument is kept in shell history and shows in the
 * process list. {@code --hash-key <key>} is still accepted for one more release: it prints the
 * fingerprint, warns that the key was exposed, and never prints the key back.
 */
final class KeygenCommand {

    private static final String GENERATE = "--generate-key";
    private static final String HASH = "--hash-key";
    private static final String HASH_FILE = "--hash-key-file";
    /** The value of {@code --hash-key} that says the key is on standard input. */
    private static final String STDIN = "-";

    private KeygenCommand() {}

    static boolean handles(String[] args) {
        for (String a : args) {
            if (GENERATE.equals(a) || HASH.equals(a) || a.startsWith(HASH + "=")
                    || HASH_FILE.equals(a) || a.startsWith(HASH_FILE + "=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs a command and returns its exit code: 0 on success, 2 for bad arguments or an unreadable
     * key. {@code in} is read only by {@code --hash-key -}.
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        String hashArg = valueOf(args, HASH);
        String fileArg = valueOf(args, HASH_FILE);
        boolean minting = contains(args, GENERATE);

        if (minting && (hashArg != null || fileArg != null)) {
            err.println("Use one of " + GENERATE + ", " + HASH + " or " + HASH_FILE + ", not both: the"
                    + " first mints a new key, the others fingerprint one you already have.");
            return 2;
        }
        if (hashArg != null && fileArg != null) {
            err.println("Use one of " + HASH + " or " + HASH_FILE + ", not both: the key comes either"
                    + " on standard input or from a file.");
            return 2;
        }

        String plaintext;
        if (hashArg == null && fileArg == null) {
            plaintext = ApiKeyGenerator.generatePlaintext();
        } else if (fileArg != null) {
            if (fileArg.isEmpty()) {
                err.println(HASH_FILE + " needs the path of the file holding the key, for example:"
                        + " java -jar <gateway jar> " + HASH_FILE + " /run/secrets/gateway-key");
                return 2;
            }
            plaintext = readKeyFile(fileArg, err);
            if (plaintext == null) {
                return 2;
            }
        } else if (!hashArg.isEmpty() && !STDIN.equals(hashArg)) {
            // The argument form: accepted for one more release, with a warning. The key is not
            // printed back; it is already in the shell history and the process list.
            warnAboutTheArgumentForm(err);
            plaintext = hashArg;
        } else {
            plaintext = readKey(in);
            if (plaintext == null) {
                err.println(HASH + " reads the key to fingerprint from standard input, and none arrived."
                        + " For example: printf '%s' \"$KEY\" | java -jar <gateway jar> " + HASH + " " + STDIN);
                return 2;
            }
        }

        String name = valueOf(args, "--name");
        String workspace = valueOf(args, "--workspace");
        List<String> scopes = scopesOf(args, err);
        if (scopes == null) {
            return 2;
        }

        // Only a minted key is printed. A key the operator already holds is not echoed.
        if (minting) {
            out.println();
            out.println("Key — give this to whoever calls the gateway.");
            out.println("It is not stored anywhere and cannot be recovered. If you lose it, mint another.");
            out.println();
            out.println("  " + plaintext);
        }

        out.println();
        out.println("Add to gateway.yaml:");
        out.println();
        out.println("  api_keys:");
        out.println("    - key_hash: sha256:" + ApiKeyGenerator.hash(plaintext));
        if (name != null) {
            out.println("      name: " + yamlString(name));
        }
        if (workspace != null) {
            out.println("      workspace: " + yamlString(workspace));
        }
        if (!scopes.isEmpty()) {
            out.println("      scopes: [" + String.join(", ", scopes) + "]");
        }
        out.println();
        return 0;
    }

    /**
     * Warns that a key passed as an argument is exposed and says what to do about it. Written to
     * standard error so the block on standard output stays pasteable.
     */
    private static void warnAboutTheArgumentForm(PrintStream err) {
        err.println("Warning: the key was passed as an argument, so it is in your shell history and was"
                + " in the process list, readable by every user on this host, while this ran. Treat it as"
                + " exposed: mint a replacement with " + GENERATE + " and retire this one.");
        err.println("This form goes away in the next release. Pass the key on standard input —"
                + " printf '%s' \"$KEY\" | java -jar <gateway jar> " + HASH + " " + STDIN
                + " — or name a file holding it: java -jar <gateway jar> " + HASH_FILE
                + " /run/secrets/gateway-key");
    }

    /**
     * The key held in a file, with one trailing newline removed: editors and {@code echo} add one,
     * and a key carrying a newline hashes to something no caller can present. A missing, unreadable
     * or empty file is refused, so a typo in the path fails here instead of fingerprinting an empty
     * string.
     */
    private static String readKeyFile(String path, PrintStream err) {
        String content;
        try {
            content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException e) {
            err.println(HASH_FILE + ": cannot read " + path + " — it is not there, or it cannot be"
                    + " opened. It should be a file holding the key and nothing else.");
            return null;
        }
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }
        if (content.endsWith("\r")) {
            content = content.substring(0, content.length() - 1);
        }
        if (content.isBlank()) {
            err.println(HASH_FILE + ": " + path + " is empty. It should hold the key and nothing else.");
            return null;
        }
        return content;
    }

    /** The first non-blank line of standard input, trimmed; null when there is none. */
    private static String readKey(InputStream in) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    return line.trim();
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * A double-quoted YAML scalar. Printed bare, {@code ci: nightly} does not parse and
     * {@code team #blue} loses everything after the hash to a comment.
     */
    private static String yamlString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static boolean contains(String[] args, String flag) {
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** Accepts both {@code --flag value} and {@code --flag=value}. */
    private static String valueOf(String[] args, String flag) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith(flag + "=")) {
                return args[i].substring(flag.length() + 1);
            }
            if (flag.equals(args[i])) {
                return i + 1 < args.length && !args[i + 1].startsWith("--") ? args[i + 1] : "";
            }
        }
        return null;
    }

    /**
     * The scopes from {@code --scopes}, lower-cased; null after printing an error when one is unknown.
     * An unknown scope grants nothing, so a typo would silently narrow the key to a 403 at request
     * time. It is refused here instead.
     */
    private static List<String> scopesOf(String[] args, PrintStream err) {
        String raw = valueOf(args, "--scopes");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String s : raw.split(",")) {
            String scope = s.trim().toLowerCase(Locale.ROOT);
            if (scope.isEmpty()) {
                continue;
            }
            if (!ApiKeyScope.isKnown(scope)) {
                err.println("Unknown scope '" + scope + "'. Known scopes: "
                        + java.util.Arrays.stream(ApiKeyScope.values())
                                .map(ApiKeyScope::value).collect(java.util.stream.Collectors.joining(", "))
                        + ". Omit --scopes entirely for an unrestricted key.");
                return null;
            }
            out.add(scope);
        }
        return out;
    }
}
