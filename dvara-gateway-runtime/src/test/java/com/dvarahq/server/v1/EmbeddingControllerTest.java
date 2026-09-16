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
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmbeddingController.class)
@Import(TestMetricsConfig.class)
class EmbeddingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProviderDispatcher dispatcher;

    @MockitoBean
    RateLimiter rateLimiter;

    /**
     * Embeddings run the same governance preamble as chat, so the slice needs the execution
     * service. It is stubbed to hand back the request unchanged, which is what a pipeline with no
     * rewriting filter does: these tests check the endpoint's own mapping, not the filters.
     */
    @MockitoBean
    com.dvarahq.server.service.ChatExecutionService chatExecutionService;

    @org.junit.jupiter.api.BeforeEach
    void passThroughGovernance() {
        when(chatExecutionService.prepare(any(), any(), any(), any()))
                .thenAnswer(inv -> new com.dvarahq.server.service.ChatExecutionService.Prepared(
                        inv.getArgument(0), com.dvarahq.core.filter.FilterContext.builder().build()));
    }

    @Test
    void happyPath_returns200WithEmbeddingData() throws Exception {
        EmbeddingResponse embeddingResponse = EmbeddingResponse.builder()
                .object("list")
                .model("text-embedding-ada-002")
                .data(List.of(
                        EmbeddingResponse.EmbeddingData.builder()
                                .object("embedding")
                                .index(0)
                                .embedding(List.of(0.1, -0.05, 0.23))
                                .build()
                ))
                .usage(EmbeddingResponse.Usage.builder()
                        .promptTokens(5)
                        .totalTokens(5)
                        .build())
                .build();

        when(dispatcher.embed(any())).thenReturn(embeddingResponse);

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "text-embedding-ada-002",
                                  "input": "Hello DVARA"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-ID"))
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.model").value("text-embedding-ada-002"))
                .andExpect(jsonPath("$.data[0].object").value("embedding"))
                .andExpect(jsonPath("$.data[0].index").value(0))
                .andExpect(jsonPath("$.data[0].embedding[0]").value(0.1))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(5))
                .andExpect(jsonPath("$.usage.total_tokens").value(5));
    }

    @Test
    void dimensions_reachTheProviderRatherThanBeingDroppedAsAnUnknownField() throws Exception {
        // An unknown JSON property is not an error, so if the DTO dropped this field a caller asking
        // for 256-dimension vectors would be served full-width ones with a 200 and nothing said.
        when(dispatcher.embed(any())).thenReturn(EmbeddingResponse.builder()
                .object("list").model("text-embedding-3-small")
                .data(List.of(EmbeddingResponse.EmbeddingData.builder()
                        .object("embedding").index(0).embedding(List.of(0.1)).build()))
                .usage(EmbeddingResponse.Usage.builder().promptTokens(2).totalTokens(2).build())
                .build());

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "text-embedding-3-small",
                                  "input": "Hello DVARA",
                                  "dimensions": 256
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<com.dvarahq.core.model.EmbeddingRequest> sent =
                ArgumentCaptor.forClass(com.dvarahq.core.model.EmbeddingRequest.class);
        verify(dispatcher).embed(sent.capture());
        assertThat(sent.getValue().getDimensions()).isEqualTo(256);
    }

    @Test
    void encodingFormatBase64_isRefusedRatherThanIgnored() throws Exception {
        // base64 asks for each vector as a string; this endpoint returns an array of numbers and
        // could not carry it. Relaying the request would answer 200 in a shape nobody asked for.
        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "text-embedding-3-small",
                                  "input": "Hello DVARA",
                                  "encoding_format": "base64"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void encodingFormatFloat_isTheDefaultAndIsAccepted() throws Exception {
        when(dispatcher.embed(any())).thenReturn(EmbeddingResponse.builder()
                .object("list").model("text-embedding-3-small")
                .data(List.of(EmbeddingResponse.EmbeddingData.builder()
                        .object("embedding").index(0).embedding(List.of(0.1)).build()))
                .usage(EmbeddingResponse.Usage.builder().promptTokens(2).totalTokens(2).build())
                .build());

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "text-embedding-3-small",
                                  "input": "Hello DVARA",
                                  "encoding_format": "float"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void missingModel_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input": "hello"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("model"));
    }

    @Test
    void missingInput_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "text-embedding-ada-002"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void noEmbeddingProvider_returns400() throws Exception {
        when(dispatcher.embed(any()))
                .thenThrow(new GatewayException("NO_PROVIDER",
                        "No provider configured for embedding model: text-embedding-ada-002."));

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "text-embedding-ada-002", "input": "hello"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("no_provider"));
    }

    @Test
    void incomingTraceId_isEchoedBack() throws Exception {
        when(dispatcher.embed(any())).thenReturn(emptyEmbeddingResponse());

        mockMvc.perform(post("/v1/embeddings")
                        .header("X-Trace-ID", "trace-embed-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "text-embedding-ada-002", "input": "hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-ID", "trace-embed-001"));
    }

    private static EmbeddingResponse emptyEmbeddingResponse() {
        return EmbeddingResponse.builder()
                .object("list")
                .model("text-embedding-ada-002")
                .data(List.of())
                .usage(EmbeddingResponse.Usage.builder().build())
                .build();
    }

    // ── governance wiring ─────────────────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    void governance_runsBeforeDispatch() throws Exception {
        when(dispatcher.embed(any())).thenReturn(emptyEmbeddingResponse());

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\": \"text-embedding-ada-002\", \"input\": \"hello\"}"))
                .andExpect(status().isOk());

        // prepare() is the only way an embedding request reaches the filter pipeline.
        org.mockito.Mockito.verify(chatExecutionService)
                .prepare(any(), any(), any(), any());
    }

    @org.junit.jupiter.api.Test
    void redactedText_isWhatReachesTheProvider() throws Exception {
        // preDispatch returns a possibly rewritten request. A controller that ran the pipeline and
        // then embedded the original text would pass the test above and still send the personal
        // data upstream.
        when(chatExecutionService.prepare(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    com.dvarahq.core.model.ChatRequest in = inv.getArgument(0);
                    com.dvarahq.core.model.ChatRequest rewritten = com.dvarahq.core.model.ChatRequest.builder()
                            .model(in.getModel())
                            .messages(List.of(com.dvarahq.core.model.MultimodalMessage.user(
                                    "my ssn is {{PII_SSN_abc123}}")))
                            .build();
                    return new com.dvarahq.server.service.ChatExecutionService.Prepared(
                            rewritten, com.dvarahq.core.filter.FilterContext.builder().build());
                });
        when(dispatcher.embed(any())).thenReturn(emptyEmbeddingResponse());

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\": \"text-embedding-ada-002\", \"input\": \"my ssn is 123-45-6789\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.dvarahq.core.model.EmbeddingRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.model.EmbeddingRequest.class);
        org.mockito.Mockito.verify(dispatcher).embed(captor.capture());

        assertThat(captor.getValue().getInput().toString())
                .as("the redacted text is what is embedded, not the original")
                .contains("{{PII_SSN_abc123}}")
                .doesNotContain("123-45-6789");
    }

    @org.junit.jupiter.api.Test
    void aStringInputStaysAString() throws Exception {
        // Some providers key their response indices off the input shape, so a string that arrives
        // as a one-element list is a wire change, not a formatting detail.
        when(dispatcher.embed(any())).thenReturn(emptyEmbeddingResponse());

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\": \"text-embedding-ada-002\", \"input\": \"hello\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.dvarahq.core.model.EmbeddingRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.model.EmbeddingRequest.class);
        org.mockito.Mockito.verify(dispatcher).embed(captor.capture());
        assertThat(captor.getValue().getInput()).isInstanceOf(String.class);
    }

    @org.junit.jupiter.api.Test
    void tokenIdInput_passesThroughUngoverned() throws Exception {
        // Token arrays carry nothing a PII or guardrail scanner can read. Coercing them into text
        // would have a filter scan integers as prose; passing them through unchanged is correct.
        when(dispatcher.embed(any())).thenReturn(emptyEmbeddingResponse());

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\": \"text-embedding-ada-002\", \"input\": [1043, 8890, 12]}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.dvarahq.core.model.EmbeddingRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.model.EmbeddingRequest.class);
        org.mockito.Mockito.verify(dispatcher).embed(captor.capture());
        assertThat(captor.getValue().getInput().toString()).contains("1043");
    }

    @org.junit.jupiter.api.Test
    void priorityAdmissionSlot_isAlwaysReleased() throws Exception {
        // The slot is acquired in preDispatch. A leaked slot saturates the tier permanently, and the
        // failure shows up long after the request that caused it, on unrelated traffic.
        when(dispatcher.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "upstream down"));

        mockMvc.perform(post("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\": \"text-embedding-ada-002\", \"input\": \"hello\"}"))
                .andExpect(status().isBadGateway());   // PROVIDER_ERROR → 502, not 4xx

        org.mockito.Mockito.verify(chatExecutionService).releasePriority(any());
    }
}