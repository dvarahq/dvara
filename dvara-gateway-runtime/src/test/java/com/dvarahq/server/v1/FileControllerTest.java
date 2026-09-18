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

import com.dvarahq.server.TestApiKey;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.BatchExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileController.class)
@Import(TestMetricsConfig.class)
class FileControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BatchExecutionService batchService;

    @MockitoBean
    RateLimiter rateLimiter;

    @Test
    void upload_relaysFileAndReturnsProviderJson() throws Exception {
        when(batchService.uploadFile(any(), eq("in.jsonl"), eq("batch"), eq(TestApiKey.WORKSPACE), any()))
                .thenReturn("{\"id\":\"file_1\",\"object\":\"file\"}");

        MockMultipartFile file = new MockMultipartFile(
                "file", "in.jsonl", "application/jsonl",
                "{\"custom_id\":\"1\"}\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/v1/files")
                        .file(file)
                        .param("purpose", "batch")
                        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("file_1"));

        verify(batchService).uploadFile(any(), eq("in.jsonl"), eq("batch"), eq(TestApiKey.WORKSPACE), any());
    }

    @Test
    void upload_piiBlocked_returns400() throws Exception {
        when(batchService.uploadFile(any(), any(), any(), any(), any()))
                .thenThrow(new GatewayException("PII_DETECTED", "Batch input file blocked: PII detected (EMAIL)"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "in.jsonl", "application/jsonl",
                "email user@example.com".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/v1/files").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("pii_detected"));
    }
}