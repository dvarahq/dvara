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
package com.dvarahq.core.provider;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.SseChunk;

import java.util.Iterator;
import java.util.List;

public interface LlmProvider {

    String name();

    boolean supports(ChatRequest request);

    ChatResponse chat(ChatRequest request);

    default boolean supportsEmbedding(String model) {
        return false;
    }

    default EmbeddingResponse embed(EmbeddingRequest request) {
        throw new UnsupportedOperationException("Embedding not supported by provider: " + name());
    }

    default Iterator<SseChunk> streamChat(ChatRequest request) {
        throw new UnsupportedOperationException("Streaming not supported by provider: " + name());
    }

    // -------------------------------------------------------------------------
    // Batch API passthrough — OpenAI-style /files + /batches. Providers
    // that expose a batch surface (OpenAI, Azure) override these; the defaults
    // throw so a non-batch provider selected in error fails loudly. The routing
    // gate is ProviderDispatcher's capabilities().supportsBatch() filter.
    // Bodies are relayed verbatim (raw JSON / bytes) — the gateway governs and
    // meters at the surface, it does not model the provider's batch schema.
    // -------------------------------------------------------------------------

    /**
     * Uploads a (JSONL) input file to the provider's Files API. Returns the raw provider response
     * JSON (carrying the file {@code id} a subsequent batch references).
     */
    default String uploadFile(byte[] content, String filename, String purpose) {
        throw new UnsupportedOperationException("File upload not supported by provider: " + name());
    }

    /** Submits a batch job. {@code requestJson} + the returned body are relayed verbatim. */
    default String createBatch(String requestJson) {
        throw new UnsupportedOperationException("Batch API not supported by provider: " + name());
    }

    /** Retrieves a batch job's status/object as raw provider JSON (poll path). */
    default String getBatch(String batchId) {
        throw new UnsupportedOperationException("Batch API not supported by provider: " + name());
    }

    /**
     * Asks the provider to stop an in-progress batch and returns its batch object, verbatim.
     *
     * <p>Cancelling does not undo what already ran: the provider bills the requests that completed
     * and leaves them in the output file, so a cancelled batch can still owe money. The caller
     * settles it the same way it settles any other terminal status.
     */
    default String cancelBatch(String batchId) {
        throw new UnsupportedOperationException("Batch cancel not supported by provider: " + name());
    }

    /** Downloads a file's raw content, such as a completed batch's output/error JSONL. */
    default byte[] getFileContent(String fileId) {
        throw new UnsupportedOperationException("File download not supported by provider: " + name());
    }

    ProviderCapabilities capabilities();

    /**
     * Lists models available from this provider by querying the provider's API.
     * Returns an empty list by default (providers that don't support model listing).
     */
    default List<ModelInfo> listModels() {
        return List.of();
    }
}