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
package com.dvarahq.autoconfigure.config.yaml.repo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workspace setting this build cannot act on is named at startup rather than silently accepted.
 *
 * <p>{@code Workspace.metadata} is free-form, so an unknown key is not an error and must not refuse
 * the boot. But a key that <em>looks</em> like a setting and does nothing reads as configured, which
 * is the failure worth a line of log.
 */
class InertWorkspaceSettingsAreNamedTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void capture() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(YamlConfigStore.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
    }

    private static void storeFrom(String yaml) throws Exception {
        new YamlConfigStore(new ObjectMapper(new YAMLFactory()).readValue(yaml, GatewayYamlConfig.class));
    }

    private String warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void aSettingWithNoConsumerHereIsNamed_withTheWorkspaceThatSetIt() {
        // The workspace matters as much as the key: "priority-tier does nothing here" is a fact
        // about the build; "priority-tier on acme does nothing here" is a fact about a decision
        // somebody made, which is the one worth acting on.
        assertThatCode(() -> storeFrom("""
                workspaces:
                  - id: acme
                    metadata:
                      priority-tier: premium
                      cost.downgrade-rules: "gpt-4o:gpt-4o-mini"
                """));

        assertThat(warnings())
                .contains("NOTHING IN THIS BUILD READS THEM")
                .contains("priority-tier")
                .contains("cost.downgrade-rules")
                .contains("acme");
    }

    @Test
    void settingsThisBuildDoesActOnAreNotNamed() {
        // The warning must not cry wolf: a per-workspace rate limit, PII action and guardrail action
        // are all honoured here, so naming them would teach an operator to ignore the line.
        assertThatCode(() -> storeFrom("""
                workspaces:
                  - id: acme
                    metadata:
                      rate-limit.requests-per-minute: 100
                      pii.action: REDACT
                      guardrail.action: BLOCK
                """));

        assertThat(warnings())
                .doesNotContain("NOTHING IN THIS BUILD READS THEM");
    }

    @Test
    void aWorkspaceWithNoMetadataIsQuiet() {
        assertThatCode(() -> storeFrom("""
                workspaces:
                  - id: acme
                """));

        assertThat(warnings()).doesNotContain("NOTHING IN THIS BUILD READS THEM");
    }

    @Test
    void anUnrecognisedKeyIsNotNamed_becauseTheMapIsFreeForm() {
        // Only keys with a real consumer somewhere and none here are named. An arbitrary key is
        // ordinary use of a free-form map and warning about it would be noise.
        assertThatCode(() -> storeFrom("""
                workspaces:
                  - id: acme
                    metadata:
                      team: platform
                      cost-centre: "4417"
                """));

        assertThat(warnings()).doesNotContain("NOTHING IN THIS BUILD READS THEM");
    }

    /**
     * Every key on the list is named, whether or not a class here parses it. {@code Workspace.metadata}
     * is a free-form map with no allow-list, so an operator can write every one of these and nothing
     * reads any of them. Enumerated rather than sampled, so one key cannot quietly drop off the list.
     */
    @Test
    void everySettingWithNoConsumerHereIsNamed_parsedHereOrNot() {
        assertThatCode(() -> storeFrom("""
                workspaces:
                  - id: acme
                    metadata:
                      priority-tier: premium
                      cost.per-call-max-usd: 2.5
                      cost.downgrade-threshold-pct: 80
                      cost.downgrade-rules: "gpt-4o:gpt-4o-mini"
                      cost.anomaly-threshold-pct: 250
                      budget.cold-start-posture: closed
                      approval.required-tools: "fs.*"
                      approval.required-servers: srv-1
                      approval.required-skills: "pay.*"
                      approval.required-agents: agent-7
                      approval.default-action: deny
                      approval.timeout-seconds: 300
                      agentic.loop-detection.enabled: true
                      agentic.loop-detection.auto-kill: true
                      agentic.loop-detection.repetition-threshold: 5
                      agentic.loop-detection.max-calls-per-minute: 60
                      ip-access.allowlist: "192.168.0.0/16"
                      ip-access.denylist: "10.0.0.0/8"
                      audit.store-prompts: true
                      audit.archive.enabled: true
                      audit.archive.retention-days: 90
                      credentials.require-workspace-credential: true
                """));

        String warnings = warnings();
        assertThat(warnings).contains("NOTHING IN THIS BUILD READS THEM");
        for (String key : new String[] {
                // limit and cost settings
                "priority-tier", "cost.per-call-max-usd", "cost.downgrade-threshold-pct",
                "cost.downgrade-rules", "cost.anomaly-threshold-pct", "budget.cold-start-posture",
                // approval and network settings
                "approval.required-tools", "approval.required-servers", "approval.required-skills",
                "approval.required-agents", "ip-access.allowlist", "ip-access.denylist",
                // the rest
                "approval.default-action", "approval.timeout-seconds",
                "agentic.loop-detection.enabled", "agentic.loop-detection.auto-kill",
                "agentic.loop-detection.repetition-threshold",
                "agentic.loop-detection.max-calls-per-minute",
                "audit.store-prompts", "audit.archive.enabled", "audit.archive.retention-days",
                "credentials.require-workspace-credential"}) {
            assertThat(warnings).as("%s is configurable here and read by nothing", key).contains(key);
        }
    }

    private static void assertThatCode(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
