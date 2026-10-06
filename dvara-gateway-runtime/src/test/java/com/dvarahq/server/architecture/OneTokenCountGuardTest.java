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
package com.dvarahq.server.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A token count comes from a {@code TokenEstimator}, and nowhere else. A control that divides a length
 * by four judges a different number from the context-window check, the rate limiter and the meter, and
 * an operator who compares them gets answers that disagree.
 *
 * <p>This scans the main sources of every module in the reactor for a length or a character count
 * divided by 4, outside a class that implements {@code TokenEstimator}. It is a tripwire for that
 * shape, not a proof.
 */
class OneTokenCountGuardTest {

    /** {@code text.length() / 4}, {@code chars / 4}, {@code estimatedChars/4}. */
    private static final Pattern CHARS_OVER_FOUR =
            Pattern.compile("(length\\(\\)|\\b\\w*[Cc]hars\\w*)\\s*/\\s*4(?![0-9.])");

    /**
     * Not the gateway counting a request. The mock provider stands in for an upstream, and reports
     * usage the way a provider would, from its own count.
     */
    private static final Set<String> NOT_A_GATEWAY_COUNT = Set.of("MockProvider.java");

    @Test
    void onlyATokenEstimatorTurnsCharactersIntoTokens() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> roots;
        try (Stream<Path> dirs = Files.list(root)) {
            roots = dirs.filter(d -> d.getFileName().toString().startsWith("dvara-"))
                    .map(d -> d.resolve("src/main/java")).filter(Files::isDirectory).toList();
        }
        assertThat(roots).as("the modules' main sources are found from the reactor root")
                .anyMatch(r -> r.toString().contains("dvara-gateway-policy"))
                .anyMatch(r -> r.toString().contains("dvara-gateway-autoconfigure"));

        List<String> offenders = new ArrayList<>();
        int estimators = 0;
        for (Path srcRoot : roots) {
            try (Stream<Path> files = Files.walk(srcRoot)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    if (source.contains("implements TokenEstimator")) {
                        estimators++;
                        continue;
                    }
                    if (NOT_A_GATEWAY_COUNT.contains(file.getFileName().toString())) {
                        continue;
                    }
                    List<String> lines = source.lines().toList();
                    for (int i = 0; i < lines.size(); i++) {
                        String code = lines.get(i).strip();
                        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                            continue;
                        }
                        if (CHARS_OVER_FOUR.matcher(code).find()) {
                            offenders.add(root.relativize(file) + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        assertThat(estimators).as("the estimators themselves are found, so the exemption is not vacuous")
                .isGreaterThanOrEqualTo(2);
        assertThat(offenders)
                .as("count tokens with the injected TokenEstimator, not a character count divided by four")
                .isEmpty();
    }
}
