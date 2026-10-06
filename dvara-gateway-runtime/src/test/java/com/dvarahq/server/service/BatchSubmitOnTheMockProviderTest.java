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
package com.dvarahq.server.service;

import com.dvarahq.autoconfigure.batch.InMemoryBatchJobRepository;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.batch.BatchModelCheck;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.providers.mock.MockProvider;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A batch submit against the real Mock provider, with a model check registered. The submit reads the
 * input file back from the provider and checks its models again, so the Mock must hand back the file
 * that was uploaded, not its canned batch output.
 */
class BatchSubmitOnTheMockProviderTest {

    private static final String INPUT =
            "{\"custom_id\":\"r1\",\"method\":\"POST\",\"url\":\"/v1/chat/completions\",\"body\":{\"model\":\"mock/a\"}}\n"
          + "{\"custom_id\":\"r2\",\"method\":\"POST\",\"url\":\"/v1/chat/completions\",\"body\":{\"model\":\"mock/b\"}}\n";

    /** The models turned off. A model can be turned off after its file was uploaded. */
    private final Set<String> off = new HashSet<>();

    private InMemoryBatchJobRepository jobs;
    private BatchExecutionService service;

    @BeforeEach
    void setUp() {
        MockProvider mockProvider = new MockProvider("response", 0, 0, 0.0);
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        when(dispatcher.selectBatchProvider(any())).thenReturn(mockProvider);
        PiiEnforcer pii = mock(PiiEnforcer.class);
        when(pii.enforceBlob(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        BatchModelCheck check = (workspaceId, apiKeyId, model) -> {
            if (off.contains(model)) {
                throw new GatewayException("POLICY_DENIED", "Model " + model + " is turned off.",
                        Map.of("model", model));
            }
        };
        jobs = new InMemoryBatchJobRepository();
        service = new BatchExecutionService(dispatcher, pii, TestProviders.of(null), TestProviders.of(check),
                TestProviders.of(null), jobs, mock(BatchCostBooker.class), TestProviders.of(null),
                mock(AuditWriter.class), mock(GatewayMetrics.class));
    }

    private String upload() throws Exception {
        String raw = service.uploadFile(INPUT.getBytes(StandardCharsets.UTF_8), "in.jsonl", "batch", "w1", "k1", null);
        return JsonMapper.instance().readTree(raw).path("id").asText();
    }

    @Test
    void everyModelAllowed_theSubmitPasses() throws Exception {
        String fileId = upload();

        String raw = service.createBatch("{\"input_file_id\":\"" + fileId + "\"}", "w1", "k1", null);

        String batchId = JsonMapper.instance().readTree(raw).path("id").asText();
        assertThat(jobs.findByProviderBatchIdAndWorkspaceId(batchId, "w1")).isPresent();
    }

    @Test
    void aModelTurnedOffAfterUpload_theSubmitIsRefusedNamingItsLine() throws Exception {
        String fileId = upload();
        off.add("mock/b");

        assertThatThrownBy(() -> service.createBatch("{\"input_file_id\":\"" + fileId + "\"}", "w1", "k1", null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("POLICY_DENIED");
                    assertThat(e.getDetails()).containsEntry("line", 2).containsEntry("custom_id", "r2")
                            .containsEntry("model", "mock/b");
                });
        assertThat(jobs.findByWorkspaceId("w1")).isEmpty();
    }
}
