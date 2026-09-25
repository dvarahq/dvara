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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workspace's governance settings are read through {@code Workspace.governanceSettings()}, never from
 * {@code getMetadata()} directly. A store may keep them in {@code Workspace.settings}, and a reader of the
 * metadata alone would not see them: the setting would be saved, shown and silently not applied.
 *
 * <p>This scans every module's main sources for {@code getMetadata()} called on a receiver whose name says
 * it is a workspace. It is a tripwire, not a proof: a workspace held in a variable with another name gets
 * past it. The files below read only keys an operator sets, which are not governance settings.
 */
class WorkspaceSettingsReadGuardTest {

    private static final Pattern WORKSPACE_METADATA =
            Pattern.compile("\\b(\\w*[Ww]orkspace\\w*)\\s*\\.\\s*getMetadata\\(\\)|Workspace::getMetadata");

    /** Files allowed to read a workspace's metadata, and why. */
    private static final Map<String, String> OPERATOR_KEY_READERS = Map.of(
            "WorkspaceStatusFilter.java", "reads the suspension reason",
            "MetadataWorkspaceRateLimitResolver.java", "reads rate limits",
            "YamlConfigStore.java", "is the store: it checks the keys it was given");

    @Test
    void noWorkspaceGovernanceSettingIsReadFromTheMetadataAlone() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            if (OPERATOR_KEY_READERS.containsKey(file.getFileName().toString())) continue;
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (WORKSPACE_METADATA.matcher(lines.get(i)).find()) {
                    offenders.add(file.getFileName() + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
            }
        }
        assertThat(offenders)
                .as("read a workspace's governance settings with governanceSettings(); if this file reads only "
                        + "operator keys, add it to OPERATOR_KEY_READERS with the reason")
                .isEmpty();
    }

    @Test
    void everyAllowedFileStillReadsTheMetadata() throws IOException {
        List<String> stale = new ArrayList<>(OPERATOR_KEY_READERS.keySet());
        for (Path file : mainSources()) {
            if (!OPERATOR_KEY_READERS.containsKey(file.getFileName().toString())) continue;
            for (String line : Files.readAllLines(file)) {
                Matcher m = WORKSPACE_METADATA.matcher(line);
                if (m.find()) {
                    stale.remove(file.getFileName().toString());
                    break;
                }
            }
        }
        assertThat(stale).as("these files no longer read a workspace's metadata; drop them from the list").isEmpty();
    }

    private static List<Path> mainSources() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path src : dirs.filter(d -> d.getFileName().toString().startsWith("dvara-"))
                    .map(d -> d.resolve("src/main/java")).filter(Files::isDirectory).toList()) {
                try (Stream<Path> files = Files.walk(src)) {
                    files.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
                }
            }
        }
        assertThat(out).as("main sources are found from the reactor root")
                .anyMatch(p -> p.toString().contains("dvara-gateway-policy"));
        return out;
    }
}
