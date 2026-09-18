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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's application.yml declares only keys something in this build binds. A key that binds
 * to nothing misleads: an operator sets it and concludes the feature is on. Keys bound by modules
 * this build does not carry belong in the overlay the file imports.
 *
 * <p>The allow-list is the set of prefixes the modules here bind, plus Spring's and the actuator's
 * own. A new property family is added here when its binder is added.
 */
class ApplicationYmlDeclaresOnlyWhatThisBuildBindsTest {

    private static final List<String> BOUND_HERE = List.of(
            "spring.application", "spring.profiles", "spring.config.import", "spring.servlet.multipart",
            "spring.threads", "spring.lifecycle", "server.", "management.", "springdoc.",
            // Named individually, not as a "dvara.audit." prefix: a prefix would admit any future
            // key under it without anyone checking that something binds it, which is the one thing
            // this test exists to prevent.
            "dvara.audit.hmac-secret", "dvara.audit.file.path",
            "dvara.encryption.", "dvara.actuator.", "dvara.region.",
            "dvara.llm-gateway.providers.",
            "dvara.llm-gateway.pii.", "dvara.llm-gateway.guardrail.", "dvara.llm-gateway.rate-limit.",
            "dvara.llm-gateway.cache.", "dvara.llm-gateway.routes", "dvara.llm-gateway.resilience.");

    @Test
    void everyKeyBindsInThisBuild() throws Exception {
        JsonNode root = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readString(Path.of("src/main/resources/application.yml")));
        List<String> keys = new ArrayList<>();
        flatten("", root, keys);
        assertThat(keys).isNotEmpty();
        List<String> strays = keys.stream()
                .filter(k -> BOUND_HERE.stream().noneMatch(k::startsWith))
                .toList();
        assertThat(strays).as("keys nothing in this build binds; they belong in the overlay").isEmpty();
    }

    @Test
    void theOverlayImportIsDeclaredAndOptional() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("import: optional:classpath:dvara-gateway-overlay.yml");
    }

    private static void flatten(String prefix, JsonNode node, List<String> out) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else {
            out.add(prefix);
        }
    }
}
