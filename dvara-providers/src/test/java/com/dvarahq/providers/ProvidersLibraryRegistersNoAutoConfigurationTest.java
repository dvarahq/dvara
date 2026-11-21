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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This module is a library: it registers no Spring Boot auto-configuration of its own.
 *
 * <p>An application that takes the jar gets the provider adapters as classes and builds them itself. The beans that
 * build them from configuration come from {@code dvara-gateway-autoconfigure}. So the jar must carry no
 * auto-configuration list and no auto-configuration entry in a {@code spring.factories} file.
 *
 * <p>Read from this module's own build output, not from the class path, where other modules' lists are present in
 * tests.
 */
class ProvidersLibraryRegistersNoAutoConfigurationTest {

    /** Surefire runs in the module directory. */
    private static final Path CLASSES = Path.of("target", "classes");

    @Test
    void theJarCarriesNoAutoConfigurationList() {
        assertThat(CLASSES.resolve("com/dvarahq/providers/ProviderTypes.class"))
                .as("the module's classes are where this test reads them").exists();
        assertThat(CLASSES.resolve("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
                .doesNotExist();
    }

    @Test
    void theJarRegistersNoAutoConfigurationThroughSpringFactories() throws IOException {
        Path factories = CLASSES.resolve("META-INF/spring.factories");
        if (!Files.exists(factories)) {
            return;
        }
        Properties entries = new Properties();
        try (var in = Files.newInputStream(factories)) {
            entries.load(in);
        }
        assertThat(entries.stringPropertyNames())
                .noneMatch(key -> key.startsWith("org.springframework.boot.autoconfigure."));
    }
}
