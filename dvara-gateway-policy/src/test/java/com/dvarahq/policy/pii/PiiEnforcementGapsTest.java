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

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiTokenizationService;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.pii.RegexPiiDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Three enforcement properties that fail silently when they break: no exception, no log line, no
 * failed request. Two guard against leaking personal data and one keeps function calling intact.
 */
class PiiEnforcementGapsTest {

    private static final String SSN = "123-45-6789";

    private PiiProperties properties;
    private List<AuditEvent> auditEvents;
    private StubWorkspaces workspaces;

    @BeforeEach
    void setUp() {
        auditEvents = new ArrayList<>();
        workspaces = new StubWorkspaces();
        properties = new PiiProperties();
        properties.setEnabled(true);
    }

    private PiiScanService service(PiiTokenizationService tokenization) {
        return new PiiScanService(new RegexPiiDetector(new PiiPatternRegistry()),
                tokenization, auditEvents::add, workspaces, properties);
    }

    // ------------------------------------------------------------- response-side BLOCK

    @Test
    void responseSideBlock_refusesInsteadOfReturningTheLeak() {
        // BLOCK on the response side must refuse. REDACT and TOKENIZE both remove the value, so the
        // strictest setting must not be the one that ships it.
        properties.setDefaultAction(PiiAction.BLOCK);

        assertThatThrownBy(() -> service(null).enforceResponse(responseSaying("ssn " + SSN), "acme"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("PII");

        assertThat(auditEvents)
                .as("the leak is still recorded, because refusing must not cost the evidence")
                .anyMatch(e -> "PII_OUTPUT_LEAK".equals(e.eventType()));
    }

    @Test
    void responseSideLog_stillPassesThrough() {
        properties.setDefaultAction(PiiAction.LOG);
        ChatResponse out = service(null).enforceResponse(responseSaying("ssn " + SSN), "acme");
        assertThat(text(out)).contains(SSN);
    }

    @Test
    void responseSideRedact_removesTheValue() {
        properties.setDefaultAction(PiiAction.REDACT);
        ChatResponse out = service(null).enforceResponse(responseSaying("ssn " + SSN), "acme");
        assertThat(text(out)).doesNotContain(SSN).contains("[REDACTED_");
    }

    // ------------------------------------------------------------- detokenize gating

    @Test
    void detokenizeDoesNothingUnlessTheWorkspaceTokenizes() {
        // Restoration is gated on the workspace tokenizing, not only on the auto-detokenize flag.
        // Otherwise a LOG workspace would resolve token-shaped strings the CALLER supplied, restoring
        // values this request never tokenized.
        properties.setDefaultAction(PiiAction.LOG);
        properties.setAutoDetokenizeResponse(true);
        SeededTokenization tokenizer = new SeededTokenization();
        String token = tokenizer.seed("acme", "alice@example.com");

        ChatResponse out = service(tokenizer)
                .detokenizeResponse(responseSaying("value " + token), requestSaying(token), "acme");

        assertThat(text(out)).contains(token).doesNotContain("alice@example.com");
    }

    @Test
    void detokenizeOnABuildWithNoVault_doesNotThrow() {
        // With no tokenizer there is nothing to restore from, and a token-shaped string in a
        // request must not make restoration dereference null.
        properties.setDefaultAction(PiiAction.REDACT);
        properties.setAutoDetokenizeResponse(true);
        String tokenShaped = "{{PII_EMAIL_abcd1234}}";

        assertThatCode(() -> service(null)
                .detokenizeResponse(responseSaying(tokenShaped), requestSaying(tokenShaped), "acme"))
                .doesNotThrowAnyException();
    }

    @Test
    void detokenizeStillWorksWhenTheWorkspaceTokenizes() {
        properties.setDefaultAction(PiiAction.TOKENIZE);
        properties.setAutoDetokenizeResponse(true);
        SeededTokenization tokenizer = new SeededTokenization();
        String token = tokenizer.seed("acme", "alice@example.com");

        ChatResponse out = service(tokenizer)
                .detokenizeResponse(responseSaying("value " + token), requestSaying(token), "acme");

        assertThat(text(out)).contains("alice@example.com");
    }

    // ------------------------------------------------------------- field preservation

    @Test
    void redactingARequestKeepsEveryFieldItDidNotTouch() {
        // A request carrying PII must keep its function-calling setup, or it goes upstream as a
        // plain chat call: nothing fails, and the response is simply never a tool call.
        properties.setDefaultAction(PiiAction.REDACT);

        ToolDefinition tool = ToolDefinition.builder().name("lookup").description("d").build();
        // Arguments that CONTAIN PII, not "{}". The tool call surviving redaction says nothing about
        // whether its contents were redacted, which is where the personal data actually is.
        ToolCall call = ToolCall.builder().id("call_1").name("lookup")
                .arguments("{\"note\":\"ssn " + SSN + "\"}").build();

        ChatRequest in = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("my ssn is " + SSN)))
                        .toolCalls(List.of(call))
                        .toolCallId("call_0")
                        .name("alice")
                        .build()))
                .topP(0.42)
                .frequencyPenalty(0.5)
                .tools(List.of(tool))
                .toolChoice("auto")
                .build();

        ChatRequest out = service(null).enforceRequest(in, "acme");

        assertThat(out.getMessages().getFirst().getContent().toString()).doesNotContain(SSN);
        assertThat(out.getTopP()).isEqualTo(0.42);
        assertThat(out.getToolChoice()).isEqualTo("auto");
        assertThat(out.getMessages().getFirst().getToolCallId()).isEqualTo("call_0");
        assertThat(out.getMessages().getFirst().getName()).isEqualTo("alice");
        assertThat(out.getFrequencyPenalty()).isEqualTo(0.5);
        assertThat(out.getTools())
                .as("without the tool definitions the provider cannot return a tool call at all")
                .containsExactly(tool);
        assertThat(out.getMessages().getFirst().getToolCalls()).hasSize(1);
        ToolCall outCall = out.getMessages().getFirst().getToolCalls().getFirst();
        assertThat(outCall.getId()).isEqualTo("call_1");
        assertThat(outCall.getName()).isEqualTo("lookup");
        assertThat(outCall.getArguments())
                .as("the arguments are scanned by scanRequest, so they must also be rewritten — "
                        + "detecting and auditing a removal that did not happen is worse than not "
                        + "looking")
                .doesNotContain(SSN)
                .contains("[REDACTED_SSN]");
    }

    @Test
    void piiOnlyInToolArguments_onAMessageWithNoContent_isStillRedacted() {
        // An assistant turn that IS a tool call has null content, so the one message whose entire
        // payload lives in the arguments must not be skipped by the null-content guard.
        properties.setDefaultAction(PiiAction.REDACT);

        ChatRequest in = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("assistant")
                        .content(null)
                        .toolCalls(List.of(ToolCall.builder().id("c1").name("send")
                                .arguments("{\"body\":\"ssn " + SSN + "\"}").build()))
                        .build()))
                .build();

        ChatRequest out = service(null).enforceRequest(in, "acme");

        assertThat(out.getMessages().getFirst().getToolCalls().getFirst().getArguments())
                .doesNotContain(SSN)
                .contains("[REDACTED_SSN]");
    }

    @Test
    void modelGeneratedToolArguments_inTheResponse_areScannedAndRedacted() {
        // A model that puts personal data in a function call's arguments must be detected and
        // rewritten on the way back, even though the message's content is null.
        properties.setDefaultAction(PiiAction.REDACT);

        ChatResponse in = ChatResponse.builder()
                .choices(List.of(ChatResponse.Choice.builder()
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(null)
                                .toolCalls(List.of(ToolCall.builder().id("c1").name("send")
                                        .arguments("{\"body\":\"ssn " + SSN + "\"}").build()))
                                .build())
                        .build()))
                .build();

        ChatResponse out = service(null).enforceResponse(in, "acme");

        assertThat(out.getChoices().getFirst().getMessage().getToolCalls().getFirst().getArguments())
                .doesNotContain(SSN)
                .contains("[REDACTED_SSN]");
        assertThat(auditEvents).anyMatch(e -> "PII_OUTPUT_LEAK".equals(e.eventType()));
    }

    // ------------------------------------------------------------- fixtures

    private static ChatResponse responseSaying(String text) {
        return ChatResponse.builder()
                .choices(List.of(ChatResponse.Choice.builder()
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock(text)))
                                .build())
                        .build()))
                .build();
    }

    private static ChatRequest requestSaying(String text) {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock(text)))
                        .build()))
                .build();
    }

    private static String text(ChatResponse response) {
        return response.getChoices().getFirst().getMessage().getContent().toString();
    }

    /**
     * A tokenization service that can be pre-seeded, so a test can restore without minting first.
     *
     * <p>It restores every token it knows, which is all these tests need: what is under test here is
     * whether {@link PiiScanService} DECIDES to detokenize, not how restoration works. The scoping
     * rule, the unresolved reporting and the audit events belong to a real tokenization service and
     * are tested with one.</p>
     */
    private static final class SeededTokenization implements PiiTokenizationService {
        private final Map<String, String> byToken = new ConcurrentHashMap<>();
        private int counter = 0;

        String seed(String workspaceId, String original) {
            String token = "{{PII_EMAIL_" + String.format("%08x", ++counter) + "}}";
            byToken.put(workspaceId + "::" + token, original);
            return token;
        }

        @Override
        public String tokenize(String text, List<PiiEntity> entities, String workspaceId) {
            String out = text;
            for (PiiEntity e : entities) {
                out = out.replace(e.value(), seed(workspaceId, e.value()));
            }
            return out;
        }

        @Override
        public ChatResponse detokenizeResponse(ChatResponse response, ChatRequest request,
                                               String workspaceId) {
            List<ChatResponse.Choice> choices = new ArrayList<>();
            for (var choice : response.getChoices()) {
                var msg = choice.getMessage();
                List<ContentBlock> blocks = new ArrayList<>();
                for (var b : msg.getContent()) {
                    if (b instanceof ContentBlock.TextBlock tb) {
                        String t = tb.text();
                        for (var e : byToken.entrySet()) {
                            String tok = e.getKey().substring(e.getKey().indexOf("::") + 2);
                            if (e.getKey().startsWith(workspaceId + "::")) {
                                t = t.replace(tok, e.getValue());
                            }
                        }
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
            return ChatResponse.builder().choices(choices).build();
        }
    }

    private static final class StubWorkspaces implements WorkspaceRepository {
        private final Map<String, Workspace> store = new ConcurrentHashMap<>();

        @Override
        public Optional<Workspace> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Workspace> findAll() {
            return List.copyOf(store.values());
        }

        @Override
        public Workspace save(Workspace workspace) {
            store.put(workspace.getId(), workspace);
            return workspace;
        }

        @Override
        public boolean deleteById(String id) {
            return store.remove(id) != null;
        }

        @Override
        public boolean existsById(String id) {
            return store.containsKey(id);
        }
    }
}
