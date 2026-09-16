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
package com.dvarahq.autoconfigure.audit;

import com.dvarahq.autoconfigure.GatewayAutoConfiguration;
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@link AuditWriter} a context ends up with: exactly one. The file sink is a default that
 * stands down for any writer an application registers. These assert the outcome rather than the
 * annotations.
 */
class FileAuditWiringTest {

    @TempDir
    Path dir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class));

    @Test
    void withNoPathConfiguredEventsAreDropped() {
        runner.run(context -> {
            AuditWriter writer = context.getBean(AuditWriter.class);
            assertThat(writer)
                    .as("with no path configured the writer is the drop, not the file sink")
                    .isNotInstanceOf(FileAuditWriter.class);
            // The contract of the drop is that it does not throw.
            writer.write(AuditEvent.of("ANYTHING", Map.of()));
        });
    }

    @Test
    void aConfiguredPathProducesAChainOnDisk() {
        Path file = dir.resolve("audit.log");
        runner.withPropertyValues(
                        "dvara.audit.file.path=" + file,
                        "dvara.audit.hmac-secret=a-real-secret")
                .run(context -> {
                    assertThat(context.getBean(AuditWriter.class)).isInstanceOf(FileAuditWriter.class);
                    context.getBean(AuditWriter.class).write(AuditEvent.of("POLICY_DENIED", Map.of("rule", "r1")));

                    assertThat(Files.readAllLines(file)).hasSize(1);
                    assertThat(FileAuditChainVerifier.verify(file, "a-real-secret").valid()).isTrue();
                });
    }

    /**
     * An application that registers its own {@code AuditWriter} gets it, and only it. This is the
     * extension point for writing the record somewhere other than a local file; without the
     * condition on the default, the application's bean would collide with this one and the context
     * would fail unless it was marked {@code @Primary}.
     */
    @Test
    void anApplicationsOwnWriterReplacesTheDefault() {
        AuditWriter theirs = event -> { };
        runner.withBean("theirs", AuditWriter.class, () -> theirs)
                .withPropertyValues(
                        "dvara.audit.file.path=" + dir.resolve("unused.log"),
                        "dvara.audit.hmac-secret=a-real-secret")
                .run(context -> {
                    assertThat(context.getBeansOfType(AuditWriter.class))
                            .as("the default stands down; even a configured file path does not bring it back")
                            .hasSize(1);
                    assertThat(context.getBean(AuditWriter.class)).isSameAs(theirs);
                });
    }

    @Test
    void thereIsExactlyOneAuditWriterBean() {
        runner.withPropertyValues(
                        "dvara.audit.file.path=" + dir.resolve("one.log"),
                        "dvara.audit.hmac-secret=a-real-secret")
                .run(context -> assertThat(context.getBeansOfType(AuditWriter.class))
                        .as("exactly one AuditWriter bean, so a @Primary writer from another module "
                                + "cannot collide with it")
                        .hasSize(1));
    }

    /**
     * The shipped development secret must not sign a chain: everyone has that value, so a chain
     * signed with it is not tamper-evident against anybody.
     */
    @Test
    void theShippedDevelopmentSecretIsRefused() {
        runner.withPropertyValues(
                        "dvara.audit.file.path=" + dir.resolve("insecure.log"),
                        "dvara.audit.hmac-secret=default-dev-secret-change-in-production")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("shipped development default"));
    }

    @Test
    void aPathWithNoSecretIsRefused() {
        runner.withPropertyValues("dvara.audit.file.path=" + dir.resolve("unsigned.log"))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("chain would be unsigned"));
    }

    /**
     * The environment variables the documentation names actually reach the properties.
     *
     * <p>Every other test here sets the canonical property name, which is not what an operator
     * types. The README tells them to export {@code DVARA_AUDIT_FILE_PATH} and
     * {@code DVARA_AUDIT_HMAC_SECRET}, and until this test nothing anywhere exercised the first of
     * those names — it appeared in the README and in no other file in the repository. Relaxed
     * binding is a Spring behaviour, not ours, but a documented setting that reaches nothing is
     * this repository's most repeated defect, and "it should work" is not evidence.
     *
     * <p>The property source is a real {@link SystemEnvironmentPropertySource} under Spring's
     * reserved name, because that is the type relaxed binding keys off. A plain map source would
     * pass while proving nothing: the uppercase name would be matched literally, and the
     * translation that is actually under test would never run.
     */
    @Test
    void theDocumentedEnvironmentVariableNamesResolve() {
        Path file = dir.resolve("from-env.log");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class))
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("DVARA_AUDIT_FILE_PATH", file.toString(),
                                        "DVARA_AUDIT_HMAC_SECRET", "a-real-secret"))))
                .run(context -> {
                    assertThat(context.getBean(AuditWriter.class))
                            .as("DVARA_AUDIT_FILE_PATH must reach dvara.audit.file.path, or the "
                                    + "documented way to switch audit on silently does nothing")
                            .isInstanceOf(FileAuditWriter.class);

                    context.getBean(AuditWriter.class).write(AuditEvent.of("POLICY_DENIED", Map.of()));
                    assertThat(Files.readAllLines(file)).hasSize(1);
                    assertThat(FileAuditChainVerifier.verify(file, "a-real-secret").valid()).isTrue();
                });
    }

    /**
     * The control for the test above: with the same source and no such variable, the writer is the
     * drop. Without this, that test would pass on a build where the path is picked up from
     * somewhere else entirely and the variable is doing nothing.
     */
    @Test
    void withoutThatVariableTheWriterIsStillTheDrop() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GatewayAutoConfiguration.class))
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of("DVARA_AUDIT_HMAC_SECRET", "a-real-secret"))))
                .run(context -> assertThat(context.getBean(AuditWriter.class))
                        .isNotInstanceOf(FileAuditWriter.class));
    }
}