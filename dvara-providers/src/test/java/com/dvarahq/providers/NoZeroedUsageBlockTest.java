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
package com.dvarahq.providers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No provider may answer "the upstream said nothing" with a zeroed usage block.
 *
 * <p>{@code ChatResponse.Usage}'s three fields are primitive {@code int}, so
 * {@code Usage.builder().build()} is three zeros, which looks like a call that consumed nothing.
 * The metering path drops a response whose total is not positive, so such a call gets no usage row
 * and no cost row. A source scan catches the provider nobody thought to test individually.
 */
class NoZeroedUsageBlockTest {

    private static final String FORBIDDEN = "Usage.builder().build()";

    @Test
    @DisplayName("no provider builds an empty Usage; absent usage is null")
    void noProviderBuildsAZeroedUsageBlock() throws IOException {
        Path providers = Path.of("src/main/java/com/dvarahq/providers");
        assertThat(providers).as("the provider sources this test scans").exists();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(providers)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains(FORBIDDEN)) {
                    offenders.add(providers.relativize(file).toString());
                }
            }
        }

        assertThat(offenders)
                .as("%s says the upstream reported nothing by claiming it consumed nothing; "
                        + "leave the field null instead", FORBIDDEN)
                .isEmpty();
    }
}
