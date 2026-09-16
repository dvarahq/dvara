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
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiTokenizationService;
import com.dvarahq.core.pii.PiiTokenizationUnavailableException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens to {@code TOKENIZE} when a token cannot be minted.
 *
 * <p>This is the one place the PII pipeline deliberately does <b>not</b> fail open, so the tests are
 * written around that asymmetry rather than around the happy path. The condition is real: a pod
 * with no reachable control plane and no cached DEK has nothing to encrypt an original under.
 */
class PiiTokenizeDegradeTest {

    // Every case here is TOKENIZE. Degradation fires when a token cannot be MINTED, which needs a
    // key, a store and a reachable control plane. Redaction needs none of those and cannot fail, so
    // pii.degraded-action applies to TOKENIZE alone.
    //
    // The wire strings PII_REDACT_DEGRADED and PII_REDACT_UNAVAILABLE are part of the audit and
    // error contract and are pinned here deliberately, so an accidental rename fails this suite
    // rather than silently breaking every SIEM rule and client branch keyed on them.


    /**
     * A tokenization service that cannot mint: the no-DEK / at-the-bound condition.
     *
     * <p>Two methods, because {@link PiiTokenizationService} declares two.</p>
     */
    private static final class UnmintableTokenization implements PiiTokenizationService {
        @Override
        public String tokenize(String text, java.util.List<com.dvarahq.core.pii.PiiEntity> entities,
                               String workspaceId) {
            throw new PiiTokenizationUnavailableException(
                    "no data-encryption key available for workspace " + workspaceId);
        }

        @Override
        public com.dvarahq.core.model.ChatResponse detokenizeResponse(
                com.dvarahq.core.model.ChatResponse response,
                com.dvarahq.core.model.ChatRequest request, String workspaceId) {
            return response;
        }
    }

    private static final class StubWorkspaceRepository implements WorkspaceRepository {
        private final ConcurrentHashMap<String, Workspace> store = new ConcurrentHashMap<>();

        void add(Workspace workspace) {
            store.put(workspace.getId(), workspace);
        }

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

    private PiiScanService service;
    private List<AuditEvent> auditEvents;
    private StubWorkspaceRepository workspaces;

    @BeforeEach
    void setUp() {
        PiiTokenizationService unmintable = new UnmintableTokenization();
        auditEvents = new ArrayList<>();
        workspaces = new StubWorkspaceRepository();
        PiiProperties properties = new PiiProperties();
        properties.setEnabled(true);
        properties.setDefaultAction(PiiAction.TOKENIZE);
        service = new PiiScanService(new RegexPiiDetector(new PiiPatternRegistry()),
                unmintable, auditEvents::add, workspaces, properties);
    }

    private static ChatRequest withPii() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("my ssn is 123-45-6789")))
                        .build()))
                .build();
    }

    private void workspaceWith(String id, Map<String, Object> metadata) {
        workspaces.add(Workspace.builder().id(id).name(id).metadata(metadata).build());
    }

    private AuditEvent degradeEvent() {
        return auditEvents.stream()
                .filter(e -> "PII_REDACT_DEGRADED".equals(e.eventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no PII_REDACT_DEGRADED event: " + auditEvents));
    }

    @Test
    void byDefaultTheRequestIsRefusedRatherThanServedWithPiiIntact() {
        workspaceWith("acme", Map.of());

        // Refusing is the recoverable direction — the client retries and nothing is disclosed.
        // Serving is not: there is no recall, and the workspace cannot see it happen because the
        // control plane being down is the reason it happened.
        assertThatThrownBy(() -> service.enforceRequest(withPii(), "acme"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("tokenization is unavailable");
    }

    @Test
    void aWorkspaceWithNoMetadataAtAllStillBlocks() {
        // The common case on the day this first fires: nobody has configured anything, and the
        // default has to be the safe one without being opted into.
        assertThatThrownBy(() -> service.enforceRequest(withPii(), "never-configured"))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void aWorkspaceCanOptIntoServingTheRequestInstead() {
        workspaceWith("acme", Map.of("pii.degraded-action", "LOG"));

        ChatRequest served = service.enforceRequest(withPii(), "acme");

        // Availability over confidentiality, for a workspace that said so in advance. The PII goes
        // through unredacted, which is exactly why it takes an explicit setting.
        assertThat(served).isNotNull();
        assertThat(degradeEvent().payload()).containsEntry("exposed", true);
    }

    @Test
    void anUnreadableSettingBlocksRatherThanExposing() {
        workspaceWith("acme", Map.of("pii.degraded-action", "yes-please"));

        // A typo must not open the door. Failing towards disclosure on an unparseable value is the
        // one outcome that cannot be walked back.
        assertThatThrownBy(() -> service.enforceRequest(withPii(), "acme"))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void theDegradeIsAuditedUnderItsOwnEventTypeEvenWhenBlocking() {
        workspaceWith("acme", Map.of());

        assertThatThrownBy(() -> service.enforceRequest(withPii(), "acme"))
                .isInstanceOf(GatewayException.class);

        // Distinct from PII_DETECTED on purpose: under LOG this records an actual disclosure and has
        // to be findable as one; under BLOCK it is the reason a request the workspace expected to
        // succeed did not.
        AuditEvent event = degradeEvent();
        assertThat(event.payload()).containsEntry("degraded_action", "BLOCK");
        assertThat(event.payload()).containsEntry("exposed", false);
        assertThat(event.payload()).containsKey("reason");
    }

    @Test
    void theTrailNeverClaimsTokenizationThatDidNotHappen() {
        workspaceWith("acme", Map.of("pii.degraded-action", "LOG"));

        service.enforceRequest(withPii(), "acme");

        // PII_TOKENIZED is emitted only after a token is successfully minted. Emitting it up front
        // would put a claim in the audit trail that tokenization occurred, on the exact request
        // where it did not, and that trail is what a compliance review reads.
        //
        // PII_REDACTED is a different claim, and this path never emits it either: TOKENIZE says the
        // value is recoverable by a key holder, REDACT says it is gone.
        assertThat(auditEvents)
                .noneMatch(e -> "PII_TOKENIZED".equals(e.eventType()))
                .noneMatch(e -> "PII_REDACTED".equals(e.eventType()));
    }

    @Test
    void theAuditRecordsWhatWasExposedWithoutRecordingTheValues() {
        workspaceWith("acme", Map.of("pii.degraded-action", "LOG"));

        service.enforceRequest(withPii(), "acme");

        AuditEvent event = degradeEvent();
        assertThat(event.payload()).containsKey("entity_types");
        assertThat(event.payload().get("entity_count")).isEqualTo(1);
        // The same rule the rest of this class follows: types and counts, never the value itself.
        assertThat(event.payload().toString()).doesNotContain("123-45-6789");
    }

    @Test
    void theEventSaysWhichActionCouldNotBeHonoured() {
        // The event type says REDACT: it is part of the audit contract and SIEM rules key on it, so
        // it may be replaced only through an explicitly versioned contract migration. REDACT itself
        // cannot reach this state, since it stores nothing and needs no key, so attempted_action is
        // what stops a compliance reader inferring the wrong action.
        workspaceWith("acme", Map.of("pii.degraded-action", "LOG"));

        service.enforceRequest(withPii(), "acme");

        assertThat(degradeEvent().payload()).containsEntry("attempted_action", "TOKENIZE");
    }

    // ----------------------------------------------------------- wire contract

    @Test
    void theRefusalCarriesTheLegacyErrorCode() {
        // Pinned so a rename fails here rather than silently breaking every client branching on
        // error.code. The code names REDACT although the action is TOKENIZE; it is replaceable only
        // through an explicitly versioned contract migration, and the accurate action is carried in
        // attempted_action on both the audit event and the HTTP body.
        workspaceWith("acme", Map.of());

        assertThatThrownBy(() -> service.enforceRequest(withPii(), "acme"))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode())
                        .isEqualTo("PII_REDACT_UNAVAILABLE"));
    }

    @Test
    void theRefusalMessageNamesTokenizationAndOffersRedact() {
        // The code does not name the action that failed, so the message is the only prose the caller
        // sees. It has to say
        // which action failed, and that REDACT is the one that needs no key and always works.
        workspaceWith("acme", Map.of());

        assertThatThrownBy(() -> service.enforceRequest(withPii(), "acme"))
                .hasMessageContaining("tokenization is unavailable")
                .hasMessageContaining("REDACT needs no key")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("redaction is unavailable"));
    }

    @Test
    void bothDegradedOutcomesCarryTheAttemptedAction() {
        // BLOCK and LOG are different consequences of the same failure, and a compliance reader has
        // to be able to tell which action failed in either.
        workspaceWith("blocking", Map.of());
        assertThatThrownBy(() -> service.enforceRequest(withPii(), "blocking"))
                .isInstanceOf(GatewayException.class);
        assertThat(degradeEvent().payload())
                .containsEntry("attempted_action", "TOKENIZE")
                .containsEntry("degraded_action", "BLOCK")
                .containsEntry("exposed", false);

        auditEvents.clear();
        workspaceWith("serving", Map.of("pii.degraded-action", "LOG"));
        service.enforceRequest(withPii(), "serving");
        assertThat(degradeEvent().payload())
                .containsEntry("attempted_action", "TOKENIZE")
                .containsEntry("degraded_action", "LOG")
                .containsEntry("exposed", true);
    }
}
