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
package com.dvarahq.policy.pii;

import com.dvarahq.core.pii.AdditionalPiiDetector;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.pii.RegexPiiDetector;
import com.dvarahq.policy.PolicyAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the PII detector is, with and without contributed layers.
 *
 * <p>Asserted against a stub layer rather than any particular detector, on purpose: what belongs
 * here is that <b>the regex detector always runs and layers are additive</b>, which is the property
 * that makes varying them by deployment safe. Which particular layer a deployment supplies is that
 * deployment's business, and is tested where it lives.</p>
 */
class PiiDetectorLayeringTest {

    private final PolicyAutoConfiguration config = new PolicyAutoConfiguration();
    private final PiiPatternRegistry registry = new PiiPatternRegistry();

    @Test
    @DisplayName("no contributions: the regex detector itself, not an empty composite")
    void regexOnlyWhenNothingIsContributed() {
        PiiDetector detector = config.piiDetector(registry, new PiiProperties(), provider());

        assertThat(detector)
                .describedAs("with nothing contributed the regex detector still runs on its own, "
                        + "which is what makes layering safe to vary by deployment")
                .isInstanceOf(RegexPiiDetector.class);
    }

    @Test
    @DisplayName("one contribution: composed with regex, which keeps running")
    void layersAreAdditive() {
        PiiDetector detector = config.piiDetector(registry, new PiiProperties(),
                provider(mock(AdditionalPiiDetector.class)));

        assertThat(detector).isInstanceOf(CompositePiiDetector.class);
    }

    /** An ObjectProvider over the given contributions; Spring supplies the real one. */
    private static ObjectProvider<AdditionalPiiDetector> provider(AdditionalPiiDetector... contributed) {
        return new ObjectProvider<>() {
            @Override
            public Stream<AdditionalPiiDetector> orderedStream() {
                return Stream.of(contributed);
            }

            @Override
            public AdditionalPiiDetector getObject() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
