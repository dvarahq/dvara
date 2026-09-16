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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiScanResult;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import com.dvarahq.pii.PiiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamingResponseEnforcerImplTest {

    private PiiDetector piiDetector;
    private GuardrailDetector guardrailDetector;
    private AuditWriter auditWriter;
    private WorkspaceRepository workspaceRepository;
    private PiiProperties piiProperties;
    private GuardrailProperties guardrailProperties;
    private StreamingResponseEnforcerImpl enforcer;

    @BeforeEach
    void setUp() {
        piiDetector = mock(PiiDetector.class);
        guardrailDetector = mock(GuardrailDetector.class);
        auditWriter = mock(AuditWriter.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        piiProperties = new PiiProperties();
        guardrailProperties = new GuardrailProperties();

        when(piiDetector.scan(any(String.class), any())).thenReturn(PiiScanResult.EMPTY);
        when(guardrailDetector.scan(any(String.class), any())).thenReturn(GuardrailScanResult.EMPTY);
        when(workspaceRepository.findById(any())).thenReturn(Optional.empty());

        enforcer = new StreamingResponseEnforcerImpl(piiDetector, guardrailDetector,
                auditWriter, workspaceRepository, piiProperties, guardrailProperties);
    }

    @Test
    void returnsPassthrough_whenBothDisabled() {
        piiProperties.setEnabled(false);
        guardrailProperties.setEnabled(false);
        enforcer = new StreamingResponseEnforcerImpl(piiDetector, guardrailDetector,
                auditWriter, workspaceRepository, piiProperties, guardrailProperties);

        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(true).build()
        ).iterator();

        Iterator<SseChunk> result = enforcer.wrap(upstream, "t1");

        // Should return the exact same iterator (not wrapped)
        assertThat(result).isSameAs(upstream);
    }

    @Test
    void returnsPassthrough_whenStreamingScanDisabled() {
        piiProperties.setScanStreamingResponses(false);
        guardrailProperties.setScanStreamingResponses(false);
        enforcer = new StreamingResponseEnforcerImpl(piiDetector, guardrailDetector,
                auditWriter, workspaceRepository, piiProperties, guardrailProperties);

        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(true).build()
        ).iterator();

        Iterator<SseChunk> result = enforcer.wrap(upstream, "t1");
        assertThat(result).isSameAs(upstream);
    }

    @Test
    void wrapsIterator_whenEnforcementEnabled() {
        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(true).build()
        ).iterator();

        Iterator<SseChunk> result = enforcer.wrap(upstream, "t1");

        // Should be a GuardedSseIterator, not the raw upstream
        assertThat(result).isNotSameAs(upstream);
        assertThat(result).isInstanceOf(GuardedSseIterator.class);
    }

    @Test
    void perWorkspaceOverride_disablesPiiScanning() {
        Workspace workspace = Workspace.builder()
                .id("t1").name("Test")
                .metadata(Map.of("pii.scan-streaming-responses", "false",
                        "guardrail.scan-streaming-responses", "false"))
                .build();
        when(workspaceRepository.findById("t1")).thenReturn(Optional.of(workspace));

        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(true).build()
        ).iterator();

        Iterator<SseChunk> result = enforcer.wrap(upstream, "t1");

        // Both disabled per-workspace → passthrough
        assertThat(result).isSameAs(upstream);
    }

    @Test
    void perWorkspaceOverride_changesPiiAction() {
        Workspace workspace = Workspace.builder()
                .id("t1").name("Test")
                .metadata(Map.of("pii.action", "BLOCK"))
                .build();
        when(workspaceRepository.findById("t1")).thenReturn(Optional.of(workspace));

        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(true).build()
        ).iterator();

        // Should wrap (enforcement still enabled, just action changed)
        Iterator<SseChunk> result = enforcer.wrap(upstream, "t1");
        assertThat(result).isNotSameAs(upstream);
    }

    @Test
    void nullWorkspaceId_usesGlobalDefaults() {
        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(true).build()
        ).iterator();

        // Should not throw, uses global config
        Iterator<SseChunk> result = enforcer.wrap(upstream, null);
        assertThat(result).isNotSameAs(upstream);
    }

    @Test
    void invalidWorkspaceMetadata_logsWarningAndUsesDefault() {
        Workspace workspace = Workspace.builder()
                .id("t1").name("Test")
                .metadata(Map.of("pii.action", "INVALID_VALUE"))
                .build();
        when(workspaceRepository.findById("t1")).thenReturn(Optional.of(workspace));

        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(true).build()
        ).iterator();

        // Should not throw, falls back to default
        Iterator<SseChunk> result = enforcer.wrap(upstream, "t1");
        assertThat(result).isNotSameAs(upstream);
    }

    @Test
    void runtimeUsesTheInjectedSharedProviderAndEngine() {
        AtomicInteger engineCalls = new AtomicInteger();
        AtomicReference<List<String>> resolvedSources = new AtomicReference<>();
        var posture = new com.dvarahq.core.enforcement.StreamingPosture(
                true, PiiAction.LOG, Map.of(), false, GuardrailAction.LOG, 1.0,
                true, GuardrailAction.LOG, List.of("source"));
        com.dvarahq.core.enforcement.StreamingEnforcementEngine engine =
                (document, resolved, workspace) -> {
                    engineCalls.incrementAndGet();
                    assertThat(resolved).isSameAs(posture);
                    return com.dvarahq.core.enforcement.EnforcementResult.allowed(document);
                };
        com.dvarahq.core.enforcement.StreamingPostureProvider provider = (workspace, sources) -> {
            resolvedSources.set(sources);
            return posture;
        };
        var runtime = new StreamingResponseEnforcerImpl(auditWriter, piiProperties,
                guardrailProperties, engine, provider,
                com.dvarahq.core.enforcement.StreamingEnforcementTelemetry.NOOP);
        var request = com.dvarahq.core.model.ChatRequest.builder()
                .metadata(Map.of("grounding.sources", List.of("source"))).build();
        Iterator<SseChunk> guarded = runtime.wrap(List.of(SseChunk.builder()
                .id("1").model("m").delta("Hello").done(true).build()).iterator(), "t1", request);

        while (guarded.hasNext()) {
            guarded.next();
        }

        assertThat(resolvedSources.get()).containsExactly("source");
        assertThat(engineCalls).hasValue(1);
    }
}
