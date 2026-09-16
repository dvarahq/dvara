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

import com.dvarahq.core.pii.AdditionalPiiDetector;
import com.dvarahq.core.pii.PiiDetector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both embedded-scanner switches on, in a build that has no embedded scanner.
 *
 * <p>The switches bind here so every build reads them at the same paths, but nothing in this build
 * provides the {@code embedded} layer. Turning them on must not stop the application from starting:
 * the missing scanner is warned about at startup and otherwise ignored, and PII detection stays
 * wired.
 */
@SpringBootTest(classes = GatewayServerApplication.class, properties = {
        "dvara.llm-gateway.pii.embedded.enabled=true",
        "dvara.llm-gateway.guardrail.embedded.enabled=true"
})
class EmbeddedScannerSwitchesWithoutAScannerTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theApplicationStartsWithBothSwitchesOnAndNoScanner() {
        assertThat(context.getBean(PiiDetector.class))
                .as("PII detection is still wired")
                .isNotNull();

        assertThat(context.getBeansOfType(AdditionalPiiDetector.class).values())
                .as("no detector provides the embedded layer")
                .noneMatch(detector -> "embedded".equals(detector.providesLayer()));
    }
}
