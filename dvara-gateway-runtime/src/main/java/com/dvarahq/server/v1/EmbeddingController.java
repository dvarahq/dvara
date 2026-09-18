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

import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.server.service.ChatExecutionService;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.EmbeddingRequest;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import com.dvarahq.server.web.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code POST /v1/embeddings}. Runs the same governance preamble as chat, via
 * {@link ChatExecutionService#prepare}, so embeddings inherit the filter chain rather than a
 * second implementation that can drift.
 *
 * <p>The input is taken back off the <em>returned</em> request: {@code preDispatch} returns a
 * possibly-rewritten request, and that is what makes PII {@code REDACT} actually redact rather
 * than merely detect. A consequence is that redaction changes the vector: a workspace on
 * {@code REDACT} gets different embeddings than one on {@code LOG} for the same input, which can
 * look like a similarity bug.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Embeddings", description = "Text embedding generation")
public class EmbeddingController {

    private final ProviderDispatcher dispatcher;
    private final RateLimiter rateLimiter;
    private final ChatExecutionService chatExecutionService;

    public EmbeddingController(ProviderDispatcher dispatcher,
                               RateLimiter rateLimiter,
                               ChatExecutionService chatExecutionService) {
        this.dispatcher = dispatcher;
        this.rateLimiter = rateLimiter;
        this.chatExecutionService = chatExecutionService;
    }

    @PostMapping("/embeddings")
    @Operation(summary = "Create embeddings", description = "Returns embedding vectors for the given input text.")
    @ApiResponse(responseCode = "200", description = "Embedding vectors returned")
    @ApiResponse(responseCode = "400", description = "Invalid request or no provider available")
    @ApiResponse(responseCode = "403", description = "Blocked by policy, PII or guardrail enforcement")
    @ApiResponse(responseCode = "402", description = "Budget cap or per-call cost ceiling exceeded")
    public ResponseEntity<com.dvarahq.server.v1.dto.EmbeddingResponse> embeddings(
            @Valid @RequestBody EmbeddingRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        rejectUnsupported(request);

        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);

        // Each input string becomes its own user message, so a filter that scans per message sees
        // the same boundaries the caller sent. Flattening them into one blob would let PII spanning
        // two inputs be detected that was never adjacent, and would make the redacted text
        // impossible to split back apart.
        // `input` is Object because the OpenAI shape allows a bare string, an array of strings, or
        // arrays of token ids. Only text can be governed — a token array carries nothing a PII or
        // guardrail scanner can read — so a non-text input yields an empty view and passes through
        // ungoverned rather than being coerced into something a filter would scan wrongly.
        List<String> inputs = toTexts(request.getInput());
        ChatRequest governanceView = ChatRequest.builder()
                .model(request.getModel())
                .messages(inputs.stream().map(MultimodalMessage::user).toList())
                .build();

        ChatExecutionService.Prepared prep =
                chatExecutionService.prepare(governanceView, httpRequest, httpResponse, traceId);
        FilterContext ctx = prep.ctx();

        // Take the input back off the RETURNED request — this is what applies a REDACT rather than
        // merely logging it. A filter that rewrote nothing yields the original text unchanged.
        Object governedInput = rebuildInput(request.getInput(), extractInputs(prep.request(), inputs));

        try {
            com.dvarahq.core.model.EmbeddingRequest internal =
                    com.dvarahq.core.model.EmbeddingRequest.builder()
                            .model(prep.request().getModel())
                            .input(governedInput)
                            .user(request.getUser())
                            .dimensions(request.getDimensions())
                            .build();

            EmbeddingResponse resp = dispatcher.embed(internal);

            int totalTokens = resp.getUsage() != null ? resp.getUsage().getTotalTokens() : 0;
            if (totalTokens > 0) {
                String apiKey = ApiKeyAuthFilter.requiredLimiterKey(httpRequest);
                // Reserved 0: token estimation runs for chat only, so this path admits without
                // charging and settles the whole actual.
                rateLimiter.reconcileTokens(apiKey, 0, totalTokens);
            }
            // On the ledger, not just in the rate-limit window.
            chatExecutionService.persistEmbeddingUsage(httpRequest, prep.request(),
                    resp.getUsage() != null ? resp.getUsage().getPromptTokens() : 0);

            List<com.dvarahq.server.v1.dto.EmbeddingResponse.EmbeddingData> data =
                    resp.getData().stream()
                            .map(d -> com.dvarahq.server.v1.dto.EmbeddingResponse.EmbeddingData.builder()
                                    .object("embedding")
                                    .index(d.getIndex())
                                    .embedding(d.getEmbedding())
                                    .build())
                            .toList();

            com.dvarahq.server.v1.dto.EmbeddingResponse external =
                    com.dvarahq.server.v1.dto.EmbeddingResponse.builder()
                            .object("list")
                            .model(resp.getModel())
                            .data(data)
                            .usage(com.dvarahq.server.v1.dto.EmbeddingResponse.Usage.builder()
                                    .promptTokens(resp.getUsage() != null ? resp.getUsage().getPromptTokens() : 0)
                                    .totalTokens(totalTokens)
                                    .build())
                            .build();

            return ResponseEntity.ok()
                    .header(TraceIdFilter.HEADER, traceId)
                    .body(external);
        } finally {
            // Priority admission acquires a slot in preDispatch; without this an embeddings call
            // would hold it for the life of the process and the tier would saturate permanently.
            chatExecutionService.releasePriority(ctx);
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * Refuses what this endpoint cannot honour, rather than dropping it. Only {@code encoding_format}
     * is refusable here, and only a value other than {@code float}: {@code base64} asks for each
     * vector as a string, and this gateway's response carries an array of numbers. {@code dimensions}
     * is relayed.
     */
    private static void rejectUnsupported(EmbeddingRequest request) {
        String format = request.getEncodingFormat();
        if (format != null && !format.isBlank() && !"float".equalsIgnoreCase(format)) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "encoding_format=" + format + " is not supported; this endpoint returns each "
                    + "embedding as an array of numbers. Omit the field or send encoding_format=float.");
        }
    }

    /** Every scannable text in an {@code input}, in order; empty when the input is not text. */
    private static List<String> toTexts(Object input) {
        if (input instanceof String s) {
            return List.of(s);
        }
        if (input instanceof List<?> list && list.stream().allMatch(e -> e instanceof String)) {
            return (List<String>) list;
        }
        return List.of();
    }

    /**
     * Puts governed text back into the shape the caller sent — a string stays a string, a list stays
     * a list. Returning a list where the caller sent a string would change the provider request
     * shape, and some providers key their response indices off it.
     */
    private static Object rebuildInput(Object original, List<String> governed) {
        if (governed.isEmpty()) {
            return original;
        }
        if (original instanceof String) {
            return governed.getFirst();
        }
        if (original instanceof List<?>) {
            return governed;
        }
        return original;
    }

    /**
     * Recovers one input string per message from the governed request.
     *
     * <p>Falls back to the caller's original list if the pipeline changed the message count — no
     * filter does today, and if one ever does, silently re-pairing texts to the wrong index would
     * embed the wrong thing rather than fail.</p>
     */
    private static List<String> extractInputs(ChatRequest governed, List<String> original) {
        if (governed == null || governed.getMessages() == null
                || governed.getMessages().size() != original.size()) {
            return original;
        }
        List<String> out = new ArrayList<>(original.size());
        for (int i = 0; i < governed.getMessages().size(); i++) {
            out.add(firstText(governed.getMessages().get(i), original.get(i)));
        }
        return out;
    }

    /** The text of a message's first {@code TextBlock}, or the caller's original if there is none. */
    private static String firstText(MultimodalMessage m, String fallback) {
        if (m == null || m.getContent() == null) {
            return fallback;
        }
        return m.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .filter(t -> t != null)
                .findFirst()
                .orElse(fallback);
    }
}