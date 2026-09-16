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
package com.dvarahq.server.v1;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.BatchExecutionService;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BatchController.class)
@Import(TestMetricsConfig.class)
class BatchControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BatchExecutionService batchService;

    @MockitoBean
    RateLimiter rateLimiter;

    @Test
    void create_relaysBodyAndWorkspaceAndReturnsRaw() throws Exception {
        when(batchService.createBatch(any(), any(), any(), any()))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"validating\"}");

        mockMvc.perform(post("/v1/batches")
                        .requestAttr("workspaceId", "t1")
                        .requestAttr(ApiKeyAuthFilter.API_KEY_ID_ATTR, "key-id-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input_file_id\":\"file_1\",\"endpoint\":\"/v1/chat/completions\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("batch_1"));

        // The controller relays the raw body, the workspace and the key's opaque id. The id is the
        // attribute ApiKeyAuthFilter sets, never the bearer token, because the job row persists it.
        verify(batchService).createBatch(
                eq("{\"input_file_id\":\"file_1\",\"endpoint\":\"/v1/chat/completions\"}"),
                eq("t1"), eq("key-id-1"), any());
    }

    @Test
    void create_withoutAKey_attributesToAnonymous() throws Exception {
        when(batchService.createBatch(any(), any(), any(), any())).thenReturn("{\"id\":\"batch_2\"}");

        mockMvc.perform(post("/v1/batches")
                        .requestAttr("workspaceId", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input_file_id\":\"file_1\",\"endpoint\":\"/v1/chat/completions\"}"))
                .andExpect(status().isOk());

        // The same sentinel the chat path writes, so the job's usage and cost rows agree.
        verify(batchService).createBatch(any(), eq("t1"), eq("anonymous"), any());
    }

    @Test
    void get_returnsRawStatus() throws Exception {
        when(batchService.getBatch("batch_1", "t1"))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"completed\"}");

        mockMvc.perform(get("/v1/batches/batch_1").requestAttr("workspaceId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    void get_notFound_returns404() throws Exception {
        when(batchService.getBatch("nope", "t1"))
                .thenThrow(new GatewayException("BATCH_NOT_FOUND", "Batch not found: nope"));

        mockMvc.perform(get("/v1/batches/nope").requestAttr("workspaceId", "t1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("batch_not_found"));
    }

    @Test
    void cancel_relaysTheProvidersBatchObject() throws Exception {
        when(batchService.cancelBatch("batch_1", "t1"))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"cancelled\"}");

        mockMvc.perform(post("/v1/batches/batch_1/cancel").requestAttr("workspaceId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
    }

    @Test
    void cancel_anotherWorkspacesBatch_is404() throws Exception {
        when(batchService.cancelBatch("batch_1", "intruder"))
                .thenThrow(new GatewayException("BATCH_NOT_FOUND", "Batch not found: batch_1"));

        mockMvc.perform(post("/v1/batches/batch_1/cancel").requestAttr("workspaceId", "intruder"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("batch_not_found"));
    }

    @Test
    void theResultsPathIsGone_becauseTheProviderApiHasNoSuchEndpoint() throws Exception {
        // Results are served by GET /v1/files/{fileId}/content, which is where a client
        // following the documented flow looks after reading output_file_id off the batch.
        mockMvc.perform(get("/v1/batches/batch_1/results").requestAttr("workspaceId", "t1"))
                .andExpect(status().isNotFound());
    }
}