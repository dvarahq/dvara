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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every unconditional {@code @Bean} in {@code GatewayAutoConfiguration} is named here.
 *
 * <p>A bean with no condition is registered on every boot and does not step aside for an
 * application's own implementation; both end up in the context and {@code @Primary} picks one.
 * This test makes that countable, and it fails in both directions: adding an unconditional bean
 * fails until it is justified here, and removing one fails until its entry goes with it.
 *
 * <p>It reads this module's own source, so editing the guarded file rebuilds the module that
 * guards it.
 */
class UnconditionalDefaultsAreDeclaredTest {

    /**
     * Bean methods in {@code GatewayAutoConfiguration} that carry no condition at all.
     *
     * <p>Empty is the intended state. An entry here is a claim that a bean should be present in
     * every posture, and needs the reasoning written beside it.
     */
    private static final Set<String> DECLARED = Set.of();

    private static final Pattern SIGNATURE = Pattern.compile("[A-Za-z0-9_.<>\\[\\]]+\\s+(\\w+)\\s*\\(");

    @Test
    @DisplayName("no unconditional bean is added to, or removed from, GatewayAutoConfiguration unnoticed")
    void everyUnconditionalBeanIsDeclared() throws IOException {
        List<String> lines = Files.readAllLines(gatewayAutoConfiguration());
        List<String> found = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).strip().equals("@Bean")) {
                continue;
            }
            boolean conditional = false;
            String signature = null;
            for (int j = i + 1; j < Math.min(i + 10, lines.size()); j++) {
                String s = lines.get(j).strip();
                // The annotation may be fully qualified, so match on its simple name.
                if (s.startsWith("@")) {
                    // Strip the arguments before taking the simple name: the last dot in
                    // "@a.b.c.ConditionalOnMissingBean(AuditWriter.class)" is inside the argument.
                    String head = s.contains("(") ? s.substring(0, s.indexOf('(')) : s;
                    head = head.substring(1);
                    String simple = head.substring(head.lastIndexOf('.') + 1);
                    if (simple.startsWith("Conditional") || simple.startsWith("Profile")) {
                        conditional = true;
                    }
                }
                if (s.startsWith("@")) {
                    continue;
                }
                if (s.contains("(")) {
                    signature = s;
                    break;
                }
            }
            if (conditional || signature == null) {
                continue;
            }
            Matcher m = SIGNATURE.matcher(signature);
            if (m.find()) {
                found.add(m.group(1));
            }
        }

        // The scanner is the thing that can silently stop working, and with DECLARED empty an
        // empty result is also the pass — so the tripwire has to be on the scan reaching the file
        // and finding bean methods, not on the unconditional subset being non-empty.
        long beanMethods = lines.stream().map(String::strip).filter("@Bean"::equals).count();
        assertThat(beanMethods)
                .describedAs("the scan found no @Bean at all in %s, so this guard is asserting "
                        + "nothing — which is the failure it exists to prevent", gatewayAutoConfiguration())
                .isGreaterThan(3);

        assertThat(found).containsExactlyInAnyOrderElementsOf(DECLARED);
    }

    /** This module's own main source, so editing the file it guards rebuilds the module that guards it. */
    private static Path gatewayAutoConfiguration() {
        Path here = Path.of("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            Path candidate = p.resolve("src/main/java/com/dvarahq/autoconfigure/GatewayAutoConfiguration.java");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("GatewayAutoConfiguration.java not found above " + here);
    }
}
