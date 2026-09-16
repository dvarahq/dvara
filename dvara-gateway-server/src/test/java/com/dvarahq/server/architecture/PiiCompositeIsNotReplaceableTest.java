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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.pii.AdditionalPiiDetector;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.pii.PiiScanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A contributed detector is added to the composite; it does not become the detector.
 *
 * <p>{@code AdditionalPiiDetector} extends {@code PiiDetector}, so a single contribution is itself
 * a {@code PiiDetector} bean and would satisfy a missing-bean condition on the composite. The
 * composite is therefore {@code @Primary} and not conditioned on the interface: layers are
 * additive, and the regex layer always runs.
 */
@SpringBootTest(classes = com.dvarahq.server.GatewayServerApplication.class,
        properties = "dvara.llm-gateway.providers.mock.enabled=true")
@Import(PiiCompositeIsNotReplaceableTest.AContributedLayer.class)
class PiiCompositeIsNotReplaceableTest {

    /**
     * What a module contributing a layer registers. A contribution is structurally a whole
     * {@code PiiDetector}, which is what this test is about.
     */
    static class ALayer implements AdditionalPiiDetector {

        private final String needle;

        ALayer(String needle) {
            this.needle = needle;
        }

        @Override
        public PiiScanResult scan(String text, Map<String, String> customPatterns) {
            if (text == null || !text.contains(needle)) {
                return PiiScanResult.EMPTY;
            }
            int at = text.indexOf(needle);
            return new PiiScanResult(
                    List.of(new PiiEntity(PiiEntityType.CUSTOM, needle, at, at + needle.length(),
                            "contributed:" + needle, 0.99)),
                    text);
        }

        @Override
        public List<PiiScanResult> scanRequest(ChatRequest request, Map<String, String> customPatterns) {
            return List.of();
        }

        @Override
        public List<PiiScanResult> scanResponse(ChatResponse response, Map<String, String> customPatterns) {
            return List.of();
        }

        @Override
        public String redact(String text, List<PiiEntity> entities) {
            return text;
        }
    }

    @TestConfiguration
    static class AContributedLayer {

        @Bean
        ALayer firstContribution() {
            return new ALayer("kumquat");
        }

        @Bean
        ALayer secondContribution() {
            return new ALayer("damson");
        }
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    PiiDetector injected;

    @Test
    @DisplayName("two contributions do not suppress the composite, and do not make injection ambiguous")
    void theCompositeSurvivesContributions() {
        assertThat(context.getBeanNamesForType(PiiDetector.class))
                .describedAs("the composite and both contributions are all registered")
                .contains("piiDetector", "firstContribution", "secondContribution");

        assertThat(injected)
                .describedAs("injection resolves without ambiguity because the composite is @Primary")
                .isNotNull();
    }

    @Test
    @DisplayName("the injected detector is the composite, not a contribution")
    void theSelectedDetectorIsTheComposite() {
        assertThat(context.getBean(PiiDetector.class))
                .describedAs("the primary is the composite")
                .isSameAs(injected);

        assertThat(injected)
                .describedAs("a contribution injected here would be the whole detector, and the "
                        + "regex layer would not run")
                .isNotInstanceOf(ALayer.class);
    }

    @Test
    @DisplayName("the always-present regex layer still runs")
    void regexStillRuns() {
        PiiScanResult found = injected.scan("write to alice@example.com about it", Map.of());

        assertThat(found.entities())
                .describedAs("the regex layer runs through the composite")
                .anySatisfy(e -> assertThat(e.type()).isEqualTo(PiiEntityType.EMAIL));
    }

    @Test
    @DisplayName("the contributed layer runs too, through the composite")
    void theContributionRunsThroughTheComposite() {
        PiiScanResult found = injected.scan("a kumquat and alice@example.com", Map.of());

        assertThat(found.entities())
                .describedAs("both layers ran and the composite merged them")
                .anySatisfy(e -> assertThat(e.value()).isEqualTo("kumquat"))
                .anySatisfy(e -> assertThat(e.type()).isEqualTo(PiiEntityType.EMAIL));
    }
}
