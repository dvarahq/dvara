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

import com.dvarahq.pii.RegexPiiDetector;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiScanServiceTest {

    private StubTokenization tokenization;
    private PiiScanService service;
    private PiiDetector detector;
    private List<AuditEvent> auditEvents;
    private StubWorkspaceRepository workspaceRepository;
    private PiiProperties properties;

    @BeforeEach
    void setUp() {
        var registry = new PiiPatternRegistry();
        detector = new RegexPiiDetector(registry);
        auditEvents = new ArrayList<>();
        AuditWriter auditWriter = auditEvents::add;
        workspaceRepository = new StubWorkspaceRepository();
        properties = new PiiProperties();
        properties.setEnabled(true);
        properties.setDefaultAction(PiiAction.LOG);
        tokenization = new StubTokenization();
        service = new PiiScanService(detector, tokenization, auditWriter, workspaceRepository, properties);
    }

    static class StubWorkspaceRepository implements WorkspaceRepository {
        private final ConcurrentHashMap<String, Workspace> store = new ConcurrentHashMap<>();

        void addWorkspace(Workspace workspace) { store.put(workspace.getId(), workspace); }

        @Override public Optional<Workspace> findById(String id) { return Optional.ofNullable(store.get(id)); }
        @Override public List<Workspace> findAll() { return List.copyOf(store.values()); }
        @Override public Workspace save(Workspace workspace) { store.put(workspace.getId(), workspace); return workspace; }
        @Override public boolean deleteById(String id) { return store.remove(id) != null; }
        @Override public boolean existsById(String id) { return store.containsKey(id); }
    }

    // ---- enforceBlob (Batch API input-file scan) --------------------

    @Test
    void enforceBlob_logAction_returnsUnchangedAndAudits() {
        String blob = "{\"custom_id\":\"1\",\"body\":\"email me at user@example.com\"}\n{\"custom_id\":\"2\"}";
        String result = service.enforceBlob(blob, "t1");
        assertThat(result).isEqualTo(blob);
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.getFirst().eventType()).isEqualTo("PII_DETECTED");
        assertThat(auditEvents.getFirst().payload().get("source")).isEqualTo("batch_file");
    }

    @Test
    void enforceBlob_blockAction_throwsException() {
        properties.setDefaultAction(PiiAction.BLOCK);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);
        assertThatThrownBy(() -> service.enforceBlob("contact user@example.com", "t1"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Batch input file blocked");
        assertThat(auditEvents).hasSize(1);
    }

    @Test
    void enforceBlob_redactAction_redactsContent() {
        properties.setDefaultAction(PiiAction.REDACT);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);
        String result = service.enforceBlob("email user@example.com now", "t1");
        assertThat(result).doesNotContain("user@example.com");
        assertThat(result).contains("[REDACTED_EMAIL]").doesNotContain("@");
        assertThat(auditEvents.getFirst().eventType()).isEqualTo("PII_REDACTED");
    }

    @Test
    void enforceBlob_noPii_returnsUnchanged() {
        String blob = "just some harmless jsonl content with no sensitive data";
        assertThat(service.enforceBlob(blob, "t1")).isEqualTo(blob);
        assertThat(auditEvents).isEmpty();
    }

    @Test
    void enforceBlob_disabled_returnsUnchanged() {
        properties.setEnabled(false);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);
        String blob = "user@example.com";
        assertThat(service.enforceBlob(blob, "t1")).isSameAs(blob);
    }

    @Test
    void enforceRequest_logAction_returnsUnchangedAndAudits() {
        ChatRequest request = buildRequestWithPii("Contact user@example.com");

        ChatRequest result = service.enforceRequest(request, "t1");

        assertThat(result).isSameAs(request);
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.getFirst().eventType()).isEqualTo("PII_DETECTED");
    }

    @Test
    void enforceRequest_blockAction_throwsException() {
        properties.setDefaultAction(PiiAction.BLOCK);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);

        ChatRequest request = buildRequestWithPii("Contact user@example.com");

        assertThatThrownBy(() -> service.enforceRequest(request, "t1"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("PII detected");
        assertThat(auditEvents).hasSize(1);
    }

    @Test
    void enforceRequest_redactAction_redactsContent() {
        properties.setDefaultAction(PiiAction.REDACT);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);

        ChatRequest request = buildRequestWithPii("Contact user@example.com");

        ChatRequest result = service.enforceRequest(request, "t1");

        assertThat(result).isNotSameAs(request);
        String text = ((ContentBlock.TextBlock) result.getMessages().getFirst().getContent().getFirst()).text();
        assertThat(text).doesNotContain("user@example.com");
        assertThat(text).contains("[REDACTED_EMAIL]").doesNotContain("@");
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.getFirst().eventType()).isEqualTo("PII_REDACTED");
    }

    @Test
    void enforceRequest_noPii_returnsUnchanged() {
        ChatRequest request = buildRequestWithPii("Hello world, no PII here.");

        ChatRequest result = service.enforceRequest(request, "t1");

        assertThat(result).isSameAs(request);
        assertThat(auditEvents).isEmpty();
    }

    @Test
    void enforceRequest_disabled_returnsUnchanged() {
        properties.setEnabled(false);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);

        ChatRequest request = buildRequestWithPii("Contact user@example.com");
        assertThat(service.enforceRequest(request, "t1")).isSameAs(request);
    }

    @Test
    void enforceRequest_tenantOverride_usesPerWorkspaceAction() {
        Workspace workspace = Workspace.builder()
                .id("t1")
                .name("Test Workspace")
                .metadata(Map.of("pii.enabled", "true", "pii.action", "BLOCK"))
                .build();
        workspaceRepository.addWorkspace(workspace);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);

        ChatRequest request = buildRequestWithPii("Contact user@example.com");

        assertThatThrownBy(() -> service.enforceRequest(request, "t1"))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void enforceResponse_detectsOutputLeak() {
        properties.setDefaultAction(PiiAction.REDACT);
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);

        ChatResponse response = ChatResponse.builder()
                .id("resp-1").model("gpt-4o")
                .choices(List.of(ChatResponse.Choice.builder()
                        .index(0)
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock("SSN: 123-45-6789")))
                                .build())
                        .build()))
                .build();

        ChatResponse result = service.enforceResponse(response, "t1");
        String text = ((ContentBlock.TextBlock) result.getChoices().getFirst()
                .getMessage().getContent().getFirst()).text();
        assertThat(text).doesNotContain("123-45-6789");
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.getFirst().eventType()).isEqualTo("PII_OUTPUT_LEAK");
    }

    @Test
    void stripForCache_alwaysRedacts() {
        service = new PiiScanService(detector, tokenization, auditEvents::add, workspaceRepository, properties);

        ChatRequest request = buildRequestWithPii("Contact user@example.com");
        ChatRequest stripped = service.stripForCache(request, "t1");

        String text = ((ContentBlock.TextBlock) stripped.getMessages().getFirst().getContent().getFirst()).text();
        assertThat(text).doesNotContain("user@example.com");
    }

    private ChatRequest buildRequestWithPii(String text) {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("user")
                                .content(List.of(new ContentBlock.TextBlock(text)))
                                .build()
                ))
                .build();
    }

    // ---------------- opt-in response-path auto-detokenize ----------------

    private static final String TOKEN = "{{PII_EMAIL_deadbeef}}";
    private static final String ORIGINAL = "user@example.com";

    private ChatResponse buildResponse(String text) {
        return ChatResponse.builder()
                .id("resp-1").model("gpt-4o")
                .choices(List.of(ChatResponse.Choice.builder()
                        .index(0)
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock(text)))
                                .build())
                        .build()))
                .build();
    }

    private String choiceText(ChatResponse response) {
        return ((ContentBlock.TextBlock) response.getChoices().getFirst()
                .getMessage().getContent().getFirst()).text();
    }

    /**
     * A workspace that detokenizes responses, which also means one that TOKENIZES.
     *
     * <p>Restoration is gated on the action as well as the flag, so a LOG or REDACT workspace never
     * resolves token-shaped strings a caller supplied.</p>
     */
    private void enableAutoDetokenizeForWorkspace(String workspaceId) {
        workspaceRepository.addWorkspace(Workspace.builder()
                .id(workspaceId).name("Workspace")
                .metadata(Map.of("pii.auto-detokenize-response", "true",
                        "pii.action", "TOKENIZE"))
                .build());
    }

    /** A (redacted) request whose content carries the given tokens — the "allow" set. */
    private ChatRequest requestWith(String text) {
        return ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock(text)))
                        .build()))
                .build();
    }
    @Test
    void detokenizeResponse_defaultOff_returnsUnchangedNoAudit() {
        // Default: flag unset. The response stays tokenised and no audit fires.
        tokenization.seed(TOKEN, ORIGINAL, "t1");
        ChatResponse response = buildResponse("Your email is " + TOKEN + ".");

        ChatResponse result = service.detokenizeResponse(response, requestWith(TOKEN), "t1");

        assertThat(result).isSameAs(response);
        assertThat(choiceText(result)).contains(TOKEN).doesNotContain(ORIGINAL);
        assertThat(auditEvents).isEmpty();
    }

    @Test
    void detokenizeResponse_requestCarriedNoTokens_returnsUnchangedNoAudit() {
        // Request-scoped: with no tokens in the request there is nothing to restore.
        tokenization.seed(TOKEN, ORIGINAL, "t1");
        enableAutoDetokenizeForWorkspace("t1");
        ChatResponse response = buildResponse("A perfectly ordinary answer.");

        ChatResponse result = service.detokenizeResponse(response, requestWith("no pii here"), "t1");

        assertThat(result).isSameAs(response);
        assertThat(auditEvents).isEmpty();
    }

    /**
     * Stands in for a tokenization service.
     *
     * <p>These tests are about {@link PiiScanService} DECIDING whether to tokenize or detokenize.
     * How restoration actually works — the scoping rule, the unresolved reporting, the audit events
     * — belongs to a real tokenization service and is tested with one.</p>
     */
    private static final class StubTokenization
            implements com.dvarahq.core.pii.PiiTokenizationService {

        private final Map<String, String> originals = new java.util.concurrent.ConcurrentHashMap<>();

        void seed(String token, String original, String workspaceId) {
            originals.put(workspaceId + "::" + token, original);
        }

        @Override
        public String tokenize(String text, List<com.dvarahq.core.pii.PiiEntity> entities,
                               String workspaceId) {
            String out = text;
            for (var e : entities) {
                out = out.replace(e.value(), TOKEN);
                originals.put(workspaceId + "::" + TOKEN, e.value());
            }
            return out;
        }

        @Override
        public ChatResponse detokenizeResponse(ChatResponse response, ChatRequest request,
                                               String workspaceId) {
            // Returns the SAME object when nothing was restored, which is what a real tokenization
            // service does when the request carries no tokens.
            boolean changed = false;
            List<ChatResponse.Choice> choices = new ArrayList<>();
            for (var choice : response.getChoices()) {
                var msg = choice.getMessage();
                List<ContentBlock> blocks = new ArrayList<>();
                for (var b : msg.getContent()) {
                    if (b instanceof ContentBlock.TextBlock tb) {
                        String t = tb.text();
                        for (var e : originals.entrySet()) {
                            if (e.getKey().startsWith(workspaceId + "::")) {
                                t = t.replace(e.getKey().substring(workspaceId.length() + 2), e.getValue());
                            }
                        }
                        changed |= !t.equals(tb.text());
                        blocks.add(new ContentBlock.TextBlock(t));
                    } else {
                        blocks.add(b);
                    }
                }
                choices.add(ChatResponse.Choice.builder()
                        .index(choice.getIndex())
                        .message(MultimodalMessage.builder().role(msg.getRole()).content(blocks).build())
                        .finishReason(choice.getFinishReason())
                        .build());
            }
            return changed ? ChatResponse.builder().choices(choices).build() : response;
        }
    }


    /** Redaction rewrites the messages; every other field must come through. */
    @Test
    void enforceRequest_redact_carriesEveryOtherField() throws Exception {
        properties.setDefaultAction(PiiAction.REDACT);
        ChatRequest original = fullyPopulated().toBuilder()
                .messages(List.of(MultimodalMessage.user("Contact user@example.com"))).build();
        ChatRequest result = service.enforceRequest(original, "t1");
        assertThat(result.getMessages()).isNotEqualTo(original.getMessages());
        assertOtherFieldsUnchanged(original, result, "messages");
    }

    // ---- a rewrite carries every other field, by construction ----------------------------

    /** Every ChatRequest field set to a distinct value, so a dropped one is visible. */
    private static ChatRequest fullyPopulated() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hello")))
                .stream(true)
                .maxTokens(321)
                .temperature(0.35)
                .topP(0.91)
                .responseFormat(new ResponseFormat.JsonSchema("person", Map.of("type", "object"), true))
                .metadata(new java.util.HashMap<>(Map.of("workspace_id", "acme")))
                .tools(List.of(ToolDefinition.builder().name("lookup").description("d").parameters(Map.of("type", "object")).build()))
                .toolChoice("auto")
                .build();
    }

    /** Reflects over ChatRequest's fields so a field added later is checked without editing this. */
    private static void assertOtherFieldsUnchanged(ChatRequest original, ChatRequest rewritten, String... changed) throws Exception {
        java.util.Set<String> changedFields = java.util.Set.of(changed);
        int checked = 0;
        for (java.lang.reflect.Field f : ChatRequest.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || changedFields.contains(f.getName())) continue;
            f.setAccessible(true);
            assertThat(f.get(rewritten)).as("field '%s' survives the rewrite", f.getName()).isEqualTo(f.get(original));
            checked++;
        }
        assertThat(checked).as("fields compared").isGreaterThanOrEqualTo(7);
    }
}
