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
 * The boundary, asserted from the side that has to hold: no token vault wired.
 *
 * <p>Every other test in this package supplies a tokenizer of some kind. This one supplies
 * {@code null}, which is how the gateway runs when no tokenization service is configured. The two
 * claims worth pinning are that removal still works and that a request asking for TOKENIZE is
 * refused rather than quietly served as redaction.</p>
 *
 * <p>The refusal matters more than it looks. A store that discarded the mapping and returned
 * normally would give a workspace asking for reversible tokenization irreversible removal with no
 * error and no audit event. Both are "the data did not leave", so nothing downstream could tell them
 * apart, which is why the assertion here is on the exception rather than on the redacted text.</p>
 */
class TokenizeWithoutATokenizerIsRefusedTest {

    private static final String SSN = "123-45-6789";

    private PiiScanService service;
    private PiiProperties properties;
    private List<AuditEvent> auditEvents;

    @BeforeEach
    void setUp() {
        auditEvents = new ArrayList<>();
        properties = new PiiProperties();
        properties.setEnabled(true);
        // The third argument is the tokenizer, and it is null on purpose — see the class javadoc.
        service = new PiiScanService(new RegexPiiDetector(new PiiPatternRegistry()),
                null, auditEvents::add, new StubWorkspaces(), properties);
    }

    @Test
    void redactNeedsNoTokenizer_andRemovesTheValueForGood() {
        properties.setDefaultAction(PiiAction.REDACT);

        ChatRequest served = service.enforceRequest(requestWithSsn(), "acme");

        String text = firstText(served);
        assertThat(text)
                .as("the original must not survive anywhere in the outbound request")
                .doesNotContain(SSN);
        assertThat(text)
                .as("and it is a placeholder, not a token something could later resolve")
                .contains("[REDACTED_")
                .doesNotContain("{{PII_");
    }

    @Test
    void tokenizeIsRefused_ratherThanSilentlyDowngradedToRedaction() {
        properties.setDefaultAction(PiiAction.TOKENIZE);

        assertThatThrownBy(() -> service.enforceRequest(requestWithSsn(), "acme"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("REDACT");
    }

    @Test
    void detectionItselfIsUnaffected_soBlockAndLogStillWork() {
        // Worth stating, because "no token vault" is easy to read as "no PII controls". Detection
        // does not need a tokenizer; only holding an original does.
        properties.setDefaultAction(PiiAction.BLOCK);
        assertThatThrownBy(() -> service.enforceRequest(requestWithSsn(), "acme"))
                .isInstanceOf(GatewayException.class);

        properties.setDefaultAction(PiiAction.LOG);
        assertThatCode(() -> service.enforceRequest(requestWithSsn(), "acme")).doesNotThrowAnyException();
        assertThat(auditEvents)
                .as("LOG passes the request through, but it still records the detection")
                .isNotEmpty();
    }

    private static ChatRequest requestWithSsn() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("my ssn is " + SSN)))
                        .build()))
                .build();
    }

    private static String firstText(ChatRequest request) {
        return request.getMessages().getFirst().getContent().stream()
                .filter(ContentBlock.TextBlock.class::isInstance)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .findFirst()
                .orElseThrow();
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
