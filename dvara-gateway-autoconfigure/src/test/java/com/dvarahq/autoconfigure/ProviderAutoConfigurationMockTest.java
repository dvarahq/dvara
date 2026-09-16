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
package com.dvarahq.autoconfigure;

import com.dvarahq.providers.mock.CompiledMatcher;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the non-bean helpers on {@link ProviderAutoConfiguration}:
 * matcher compilation (default name generation, missing-field validation)
 * and the Mock-on-prod startup warning.
 */
class ProviderAutoConfigurationMockTest {

    private Logger autoConfigLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogAppender() {
        autoConfigLogger = (Logger) LoggerFactory.getLogger(ProviderAutoConfiguration.class);
        appender = new ListAppender<>();
        appender.start();
        autoConfigLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        autoConfigLogger.detachAppender(appender);
    }

    // ---------------------------------------------------------------------
    // compileMatcher — default name, validation
    // ---------------------------------------------------------------------

    @Test
    void compileMatcher_missingWhen_throwsWithIndexAndName() {
        GatewayProperties.MockMatcher config = new GatewayProperties.MockMatcher();
        config.setName("billing");
        config.setResponse("Your balance");
        // when is null

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProviderAutoConfiguration.compileMatcher(config, 3))
                .withMessageContaining("index 3")
                .withMessageContaining("billing")
                .withMessageContaining("missing the 'when'");
    }

    @Test
    void compileMatcher_blankWhen_throwsWithIndexAndName() {
        GatewayProperties.MockMatcher config = new GatewayProperties.MockMatcher();
        config.setName("creative");
        config.setResponse("cats");
        config.setWhen("   ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProviderAutoConfiguration.compileMatcher(config, 0))
                .withMessageContaining("index 0")
                .withMessageContaining("creative");
    }

    @Test
    void compileMatcher_missingResponse_throwsWithIndexAndName() {
        GatewayProperties.MockMatcher config = new GatewayProperties.MockMatcher();
        config.setName("weather");
        config.setWhen("request.model == 'mock/weather'");
        // response is null

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProviderAutoConfiguration.compileMatcher(config, 7))
                .withMessageContaining("index 7")
                .withMessageContaining("weather")
                .withMessageContaining("missing the 'response'");
    }

    @Test
    void compileMatcher_missingName_usesStableIndexBasedDefault() {
        GatewayProperties.MockMatcher config = new GatewayProperties.MockMatcher();
        config.setWhen("true");
        config.setResponse("ok");
        // name is null

        CompiledMatcher matcher = ProviderAutoConfiguration.compileMatcher(config, 5);

        assertThat(matcher.name()).isEqualTo("matcher-5");
    }

    @Test
    void compileMatcher_blankName_usesStableIndexBasedDefault() {
        GatewayProperties.MockMatcher config = new GatewayProperties.MockMatcher();
        config.setName("   ");
        config.setWhen("true");
        config.setResponse("ok");

        CompiledMatcher matcher = ProviderAutoConfiguration.compileMatcher(config, 2);

        assertThat(matcher.name()).isEqualTo("matcher-2");
    }

    @Test
    void compileMatcher_defaultNameIsDeterministic() {
        // Two distinct MockMatcher instances with the same index produce the same name.
        GatewayProperties.MockMatcher a = new GatewayProperties.MockMatcher();
        a.setWhen("true");
        a.setResponse("x");
        GatewayProperties.MockMatcher b = new GatewayProperties.MockMatcher();
        b.setWhen("false");
        b.setResponse("y");

        CompiledMatcher matcherA = ProviderAutoConfiguration.compileMatcher(a, 4);
        CompiledMatcher matcherB = ProviderAutoConfiguration.compileMatcher(b, 4);

        assertThat(matcherA.name()).isEqualTo(matcherB.name()).isEqualTo("matcher-4");
    }

    @Test
    void compileMatcher_explicitName_isRetained() {
        GatewayProperties.MockMatcher config = new GatewayProperties.MockMatcher();
        config.setName("my-custom-scenario");
        config.setWhen("true");
        config.setResponse("ok");

        CompiledMatcher matcher = ProviderAutoConfiguration.compileMatcher(config, 99);

        assertThat(matcher.name()).isEqualTo("my-custom-scenario");
    }

    // ---------------------------------------------------------------------
    // warnIfMockEnabledOnProd — profile allow-list + allMatch logic
    // ---------------------------------------------------------------------

    @Test
    void warnIfMockEnabledOnProd_noProfiles_noWarning() {
        MockEnvironment env = new MockEnvironment();

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void warnIfMockEnabledOnProd_devProfile_noWarning() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void warnIfMockEnabledOnProd_testProfile_noWarning() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void warnIfMockEnabledOnProd_ciProfile_noWarning() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("ci");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void warnIfMockEnabledOnProd_multipleDevProfiles_noWarning() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "local");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void warnIfMockEnabledOnProd_prodProfile_warns() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages())
                .singleElement()
                .satisfies(msg -> {
                    assertThat(msg).contains("prod");
                    assertThat(msg).contains("arbitrary code execution");
                    assertThat(msg).contains("Disable dvara.llm-gateway.providers.mock.enabled for production");
                });
    }

    @Test
    void warnIfMockEnabledOnProd_devMixedWithProd_stillWarns() {
        // A dev-like profile next to prod does not silence the warning: every active profile
        // must be dev-like for it to stay quiet.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev", "prod");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages())
                .singleElement()
                .satisfies(msg -> assertThat(msg).contains("prod"));
    }

    @Test
    void warnIfMockEnabledOnProd_caseInsensitiveProfileMatch() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("DEV", "Local");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void warnIfMockEnabledOnProd_unrecognizedProfileName_warns() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");

        ProviderAutoConfiguration.warnIfMockEnabledOnProd(env);

        assertThat(warningMessages())
                .singleElement()
                .satisfies(msg -> assertThat(msg).contains("staging"));
    }

    // ---------------------------------------------------------------------
    // resolveScenariosDir — forgiving behavior on missing/invalid paths
    // ---------------------------------------------------------------------

    @Test
    void resolveScenariosDir_nullConfigured_returnsNullNoLog() {
        Path result = ProviderAutoConfiguration.resolveScenariosDir(null);

        assertThat(result).isNull();
        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void resolveScenariosDir_blankConfigured_returnsNullNoLog() {
        Path result = ProviderAutoConfiguration.resolveScenariosDir("   ");

        assertThat(result).isNull();
        assertThat(warningMessages()).isEmpty();
    }

    @Test
    void resolveScenariosDir_nonExistentPath_warnsAndReturnsNull(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");

        Path result = ProviderAutoConfiguration.resolveScenariosDir(missing.toString());

        assertThat(result).isNull();
        assertThat(warningMessages())
                .singleElement()
                .satisfies(msg -> {
                    assertThat(msg).contains("does-not-exist");
                    assertThat(msg).contains("does not exist or is not a directory");
                });
    }

    @Test
    void resolveScenariosDir_fileInsteadOfDirectory_warnsAndReturnsNull(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("not-a-directory.groovy");
        Files.writeString(file, "// I am a file, not a directory");

        Path result = ProviderAutoConfiguration.resolveScenariosDir(file.toString());

        assertThat(result).isNull();
        assertThat(warningMessages())
                .singleElement()
                .satisfies(msg -> assertThat(msg).contains("not-a-directory.groovy"));
    }

    @Test
    void resolveScenariosDir_validDirectory_returnsAbsolutePath(@TempDir Path tmp) {
        Path result = ProviderAutoConfiguration.resolveScenariosDir(tmp.toString());

        assertThat(result).isNotNull();
        assertThat(result.isAbsolute()).isTrue();
        assertThat(result).isEqualTo(tmp.toAbsolutePath());
        assertThat(warningMessages()).isEmpty(); // this one logs at INFO, not WARN
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private java.util.List<String> warningMessages() {
        return appender.list.stream()
                .filter(evt -> evt.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}