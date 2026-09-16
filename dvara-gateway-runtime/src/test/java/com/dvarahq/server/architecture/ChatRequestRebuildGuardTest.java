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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No method that receives a {@code ChatRequest} rebuilds one field by field. A hand-rolled copy
 * drops whichever fields its author forgot, and {@code tools}, {@code toolChoice} and {@code topP}
 * are the ones that get forgotten; {@code toBuilder()} carries every field by construction.
 *
 * <p>This scans the main sources of every module in the reactor for a {@code ChatRequest.builder()}
 * call inside a method whose parameters name {@code ChatRequest}. An empty
 * {@code ChatRequest.builder().build()} and a request built from something else, such as a DTO or
 * a batch record, are fine. It is a tripwire for the single-line shape, not a proof: a signature
 * split across lines or a builder call split from its prefix would get past it.
 */
class ChatRequestRebuildGuardTest {

    /** The type as a word: {@code ChatRequest r}, {@code List<ChatRequest> rs}, {@code ChatRequest[] rs}. */
    private static final Pattern PARAM_TYPE = Pattern.compile("\\bChatRequest\\b");

    private static final Pattern METHOD =
            Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|synchronized)\\s+)*[\\w<>\\[\\],.? ]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws [\\w, .]+)?\\s*\\{");

    @Test
    void noMethodGivenAChatRequestRebuildsItByHand_inTheShapeThatExistedAt2311() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> roots;
        try (Stream<Path> dirs = Files.list(root)) {
            roots = dirs.filter(d -> d.getFileName().toString().startsWith("dvara-"))
                    .map(d -> d.resolve("src/main/java")).filter(Files::isDirectory).toList();
        }
        assertThat(roots).as("the modules' main sources are found from the reactor root")
                .anyMatch(r -> r.toString().contains("dvara-pii-detection"))
                .anyMatch(r -> r.toString().contains("dvara-gateway-core"));

        List<String> offenders = new ArrayList<>();
        for (Path srcRoot : roots) {
            try (Stream<Path> files = Files.walk(srcRoot)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (!line.contains("ChatRequest.builder()") || line.contains("ChatRequest.builder().build()")) continue;
                        String method = enclosingMethodParameters(lines, i);
                        if (method != null && PARAM_TYPE.matcher(method).find()) {
                            offenders.add(root.relativize(file) + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("methods given a ChatRequest must rewrite it with toBuilder(), never rebuild it field by field")
                .isEmpty();
    }

    /** The parameter list of the nearest method signature above {@code index}, or null. */
    private static String enclosingMethodParameters(List<String> lines, int index) {
        for (int i = index; i >= 0; i--) {
            Matcher m = METHOD.matcher(lines.get(i));
            if (m.find()) {
                return m.group(2);
            }
        }
        return null;
    }
}
