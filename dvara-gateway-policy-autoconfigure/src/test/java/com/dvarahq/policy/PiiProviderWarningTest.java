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
package com.dvarahq.policy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiScanResult;
import com.dvarahq.core.pii.AdditionalPiiDetector;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.PiiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A layer that is configured and not running has to say so.
 *
 * <p>An operator who sets a property and sees a clean startup believes advanced detection is on, so
 * {@code dvara.llm-gateway.pii.provider} must warn when the layer it names is not actually running,
 * and fall back to regex.</p>
 *
 * <p>Asserted on the emitted log line rather than on behaviour, because the warning IS the behaviour:
 * detection is correct either way, and what matters is the operator finding out.</p>
 */
class PiiProviderWarningTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PolicyAutoConfiguration.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("presidio with no endpoint warns that nothing is being called")
    void presidioWithoutEndpoint() {
        PiiProperties props = new PiiProperties();
        props.setProvider("presidio");

        build(props);

        assertThat(warnings()).anySatisfy(m -> assertThat(m)
                .contains("provider=presidio")
                .contains("presidio.endpoint is not set"));
    }

    @Test
    @DisplayName("an unrecognised provider warns rather than silently meaning regex")
    void unknownProvider() {
        // fromString swallows anything it does not recognise and answers REGEX, so "presideo" reads
        // exactly like a deliberate choice of patterns-only.
        PiiProperties props = new PiiProperties();
        props.setProvider("presideo");

        build(props);

        assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("presideo"));
    }

    @Test
    @DisplayName("presidio WITH an endpoint but no detector warns about the missing layer")
    void endpointSetButNothingContributed() {
        PiiProperties props = new PiiProperties();
        props.setProvider("presidio");
        props.getPresidio().setEndpoint("http://presidio:3000/analyze");

        build(props);

        assertThat(warnings()).anySatisfy(m -> assertThat(m)
                .contains("presidio.endpoint is set")
                .contains("no NER detector"));
        assertThat(warnings())
                .as("the endpoint IS set, so the other warning must not also fire")
                .noneSatisfy(m -> assertThat(m).contains("presidio.endpoint is not set"));
    }

    @Test
    @DisplayName("regex, configured plainly, warns about nothing")
    void plainRegexIsQuiet() {
        build(new PiiProperties());

        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("an unrelated detector does NOT suppress a warning about a different layer")
    void anUnrelatedContributionDoesNotSuppressTheWarning() {
        // Contributions say which layer they are, so a detector contributed for the presidio layer
        // must not silence the warning that the embedded scanner the operator asked for is not
        // running.
        PiiProperties props = new PiiProperties();
        props.getEmbedded().setEnabled(true);

        build(props, new StubDetector("presidio"));

        assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("embedded scanner"));
    }

    @Test
    @DisplayName("the layer's own detector does suppress its warning")
    void theRequestedLayerSuppressesItsOwnWarning() {
        PiiProperties props = new PiiProperties();
        props.getEmbedded().setEnabled(true);

        build(props, new StubDetector("embedded"));

        assertThat(warnings()).noneSatisfy(m -> assertThat(m).contains("embedded scanner"));
    }

    @Test
    @DisplayName("a detector that declares no layer suppresses nothing")
    void anUndeclaredDetectorSuppressesNothing() {
        // Blank means "answers for no configured layer". Warning is the safe direction: the
        // alternative is silence about a layer that genuinely is not running.
        PiiProperties props = new PiiProperties();
        props.getEmbedded().setEnabled(true);

        build(props, new StubDetector(""));

        assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("embedded scanner"));
    }

    @Test
    @DisplayName("embedded requested with nothing contributed does warn")
    void embeddedRequestedWithNoContribution() {
        PiiProperties props = new PiiProperties();
        props.getEmbedded().setEnabled(true);

        build(props);

        assertThat(warnings()).anySatisfy(m -> assertThat(m).contains("embedded scanner"));
    }

    /** Contributes no detections; only its declared layer matters here. */
    private record StubDetector(String layer) implements AdditionalPiiDetector {
        @Override public String providesLayer() { return layer; }
        @Override public PiiScanResult scan(String t, Map<String, String> p) { return PiiScanResult.EMPTY; }
        @Override public List<PiiScanResult> scanRequest(com.dvarahq.core.model.ChatRequest r, Map<String, String> p) { return List.of(); }
        @Override public List<PiiScanResult> scanResponse(com.dvarahq.core.model.ChatResponse r, Map<String, String> p) { return List.of(); }
        @Override public String redact(String t, List<com.dvarahq.core.pii.PiiEntity> e) { return t; }
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private PiiDetector build(PiiProperties props, AdditionalPiiDetector... extra) {
        return new PolicyAutoConfiguration()
                .piiDetector(new PiiPatternRegistry(), props, provider(extra));
    }

    private static ObjectProvider<AdditionalPiiDetector> provider(AdditionalPiiDetector... items) {
        return new ObjectProvider<>() {
            @Override public AdditionalPiiDetector getObject() { throw new UnsupportedOperationException(); }
            @Override public AdditionalPiiDetector getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public AdditionalPiiDetector getIfAvailable() { return null; }
            @Override public AdditionalPiiDetector getIfUnique() { return null; }
            @Override public Stream<AdditionalPiiDetector> stream() { return Stream.of(items); }
            @Override public Stream<AdditionalPiiDetector> orderedStream() { return Stream.of(items); }
        };
    }
}
