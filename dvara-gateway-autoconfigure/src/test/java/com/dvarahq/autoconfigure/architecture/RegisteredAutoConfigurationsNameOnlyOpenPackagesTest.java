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
package com.dvarahq.autoconfigure.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No auto-configuration this module registers may order itself against a class outside the
 * packages this repository publishes, by type or by name.
 *
 * <p>A class reference would not compile, but {@code beforeName} and {@code afterName} take strings,
 * and an unresolvable one is silently ignored. Reading the registration file rather than naming a
 * class means an auto-configuration added later is covered automatically.
 */
class RegisteredAutoConfigurationsNameOnlyOpenPackagesTest {

    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Everything we publish. A name outside {@code com.dvarahq} is somebody else's and not our rule. */
    private static final List<String> OPEN_PACKAGES = List.of(
            "com.dvarahq.core.",
            "com.dvarahq.autoconfigure.",
            "com.dvarahq.gateway.",
            "com.dvarahq.providers.",
            "com.dvarahq.policy.",
            "com.dvarahq.pii.",
            "com.dvarahq.ratelimit.",
            "com.dvarahq.server.");

    @Test
    @DisplayName("every registered auto-configuration orders itself only against classes this repository publishes")
    void noRegisteredAutoConfigurationNamesAClassOutsideThisRepository() throws Exception {
        List<String> registered = registered();
        assertThat(registered)
                .describedAs("the registration file is empty or unreadable, so this guard is "
                        + "asserting nothing — which is the failure it exists to prevent")
                .isNotEmpty();

        List<String> offences = new ArrayList<>();
        for (String className : registered) {
            Class<?> type = Class.forName(className);
            orderingNames(type)
                    .filter(name -> name.startsWith("com.dvarahq."))
                    .filter(name -> OPEN_PACKAGES.stream().noneMatch(name::startsWith))
                    .forEach(name -> offences.add(className + " orders against " + name));
        }

        assertThat(offences)
                .describedAs("a registered auto-configuration names a class its consumers do not have. "
                        + "Declare the ordering from the module that has both classes on its classpath, "
                        + "where the reference resolves.")
                .isEmpty();
    }

    /**
     * The rule is an allow-list: a name passes if it is somebody else's entirely, or if it sits under
     * one of {@link #OPEN_PACKAGES}. Any other {@code com.dvarahq} package is rejected without the
     * guard having to enumerate it.
     */
    @Test
    @DisplayName("the rule accepts a published name and rejects one this repository does not publish")
    void theRuleItselfWorks() {
        assertThat(isOpen("com.dvarahq.autoconfigure.GatewayAutoConfiguration")).isTrue();
        assertThat(isOpen("org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")).isTrue();

        assertThat(isOpen("com.dvarahq.somewhereelse.SomeAutoConfiguration")).isFalse();
        assertThat(isOpen("com.dvarahq.core.nested.TooDeep")).isTrue();
    }

    private static boolean isOpen(String name) {
        return !name.startsWith("com.dvarahq.") || OPEN_PACKAGES.stream().anyMatch(name::startsWith);
    }

    private static Stream<String> orderingNames(Class<?> type) {
        Stream<String> fromAutoConfiguration = Stream.empty();
        AutoConfiguration ac = type.getAnnotation(AutoConfiguration.class);
        if (ac != null) {
            fromAutoConfiguration = Stream.of(
                            Arrays.stream(ac.afterName()),
                            Arrays.stream(ac.beforeName()),
                            Arrays.stream(ac.after()).map(Class::getName),
                            Arrays.stream(ac.before()).map(Class::getName))
                    .flatMap(s -> s);
        }
        AutoConfigureAfter after = type.getAnnotation(AutoConfigureAfter.class);
        AutoConfigureBefore before = type.getAnnotation(AutoConfigureBefore.class);
        Stream<String> legacy = Stream.of(
                        after == null ? Stream.<String>empty() : Stream.concat(
                                Arrays.stream(after.name()), Arrays.stream(after.value()).map(Class::getName)),
                        before == null ? Stream.<String>empty() : Stream.concat(
                                Arrays.stream(before.name()), Arrays.stream(before.value()).map(Class::getName)))
                .flatMap(s -> s);
        return Stream.concat(fromAutoConfiguration, legacy);
    }

    private static List<String> registered() throws IOException {
        try (InputStream in = RegisteredAutoConfigurationsNameOnlyOpenPackagesTest.class
                .getClassLoader().getResourceAsStream(IMPORTS)) {
            if (in == null) {
                return List.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }
}
