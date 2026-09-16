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

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.batch.BatchJob;
import com.dvarahq.core.batch.BatchJobRepository;
import com.dvarahq.core.batch.BatchSubmitGate;
import com.dvarahq.core.cost.CostRecord;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the governed Batch API passthrough: PII scan + provider relay on file upload,
 * budget gate + tracking-row persist on submit, and — the load-bearing part — <b>exactly-once</b>
 * usage + cost booking on completion, computed by summing per-line usage from the provider's output
 * file (OpenAI's batch object reports only {@code request_counts}, not tokens).
 *
 * <p>Bodies are relayed verbatim; this service parses only the governed/metered fields. The whole
 * lifecycle stays pinned to the provider captured on the {@link BatchJob} at submit. Metering runs
 * on the client's completion poll (a request thread — workspace + credentials are in scope) and is
 * best-effort: a booking failure logs a WARN and does not disturb the passthrough response, and the
 * job stays unbooked so the next poll retries.</p>
 */
@Service
public class BatchExecutionService {

    private static final Logger log = LoggerFactory.getLogger(BatchExecutionService.class);

    /** Provider batch statuses at which no further work will change the outcome. */
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "expired", "cancelled", "canceled");

    /** Shared JSON reader — batch bodies are relayed verbatim; we only read a few governed fields. */
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.instance();

    private final ProviderDispatcher dispatcher;
    private final PiiEnforcer piiEnforcer;
    /** Null when no gate is registered; the check is then not made. */
    private final BatchSubmitGate submitGate;
    private final BatchJobRepository batchJobRepository;
    private final BatchCostBooker costBooker;
    /** Empty when nothing wants to hear about a usage row. */
    private final java.util.List<WorkspaceUsageListener> usageListeners;
    private final AuditWriter auditWriter;
    private final GatewayMetrics metrics;

    public BatchExecutionService(ProviderDispatcher dispatcher, PiiEnforcer piiEnforcer,
                                 org.springframework.beans.factory.ObjectProvider<BatchSubmitGate> submitGate,
                                 BatchJobRepository batchJobRepository,
                                 BatchCostBooker costBooker,
                                 org.springframework.beans.factory.ObjectProvider<WorkspaceUsageListener> usageListeners,
                                 AuditWriter auditWriter, GatewayMetrics metrics) {
        this.dispatcher = dispatcher;
        this.piiEnforcer = piiEnforcer;
        this.submitGate = submitGate.getIfAvailable();
        this.batchJobRepository = batchJobRepository;
        this.costBooker = costBooker;
        this.usageListeners = usageListeners.orderedStream().toList();
        this.auditWriter = auditWriter;
        this.metrics = metrics;
    }

    // ---- file upload ------------------------------------------------------

    /**
     * PII-scans the uploaded JSONL as a unit (BLOCK throws, REDACT rewrites) then relays it to the
     * provider's Files API. Returns the raw provider response (carrying the file id).
     *
     * <p><b>Provider consistency (v1):</b> the returned file id is provider-scoped. When more than one
     * batch-capable provider is registered (e.g. OpenAI + Azure), the client must pass the same
     * {@code providerHint} on the subsequent {@code POST /v1/batches} — otherwise submit resolves a
     * different provider that will reject the foreign file id. With a single batch provider (the
     * common case) the hint is unambiguous and can be omitted.</p>
     */
    public String uploadFile(byte[] content, String filename, String purpose, String workspaceId, String providerHint) {
        String scanned = piiEnforcer.enforceBlob(new String(content, StandardCharsets.UTF_8), workspaceId);
        LlmProvider provider = dispatcher.selectBatchProvider(providerHint);
        return provider.uploadFile(scanned.getBytes(StandardCharsets.UTF_8), filename, purpose);
    }

    // ---- submit -----------------------------------------------------------

    /** Gates the submit if a gate is registered, relays to the provider, and persists a tracking row. */
    public String createBatch(String requestJson, String workspaceId, String apiKeyId, String providerHint) {
        if (submitGate != null) {
            submitGate.check(workspaceId, apiKeyId);
        }
        LlmProvider provider = dispatcher.selectBatchProvider(providerHint);
        String raw = provider.createBatch(requestJson);

        JsonNode node = parse(raw);
        Instant now = Instant.now();
        BatchJob job = BatchJob.builder()
                .id(Ids.newId())
                .workspaceId(workspaceId)
                .apiKey(apiKeyId)
                .provider(provider.name())
                .providerBatchId(textOrNull(node, "id"))
                .inputFileId(textOrNull(node, "input_file_id"))
                .outputFileId(textOrNull(node, "output_file_id"))
                .status(textOrNull(node, "status"))
                .costBooked(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
        batchJobRepository.save(job);
        audit(workspaceId, "BATCH_SUBMITTED", Map.of(
                "provider", provider.name(),
                "provider_batch_id", nullSafe(job.getProviderBatchId()),
                "input_file_id", nullSafe(job.getInputFileId())));
        return raw;
    }

    // ---- poll (metering trigger) ------------------------------------------

    /**
     * Relays a status poll and, on the first terminal poll, books usage + cost exactly once. The
     * metering is best-effort: any failure is logged and the passthrough response still returns.
     */
    public String getBatch(String providerBatchId, String workspaceId) {
        BatchJob job = requireJob(providerBatchId, workspaceId);
        LlmProvider provider = dispatcher.selectBatchProvider(job.getProvider());
        String raw = provider.getBatch(providerBatchId);
        settle(job, provider, raw);
        return raw;
    }

    /**
     * Applies a status poll to the tracking row: on a terminal status books usage+cost exactly once
     * (via the atomic claim), otherwise records the latest status. Best-effort: a booking/DB hiccup
     * logs a WARN and never disturbs the passthrough. Shared by the poll and cancel paths.
     */
    private void settle(BatchJob job, LlmProvider provider, String rawBatchJson) {
        try {
            JsonNode node = parse(rawBatchJson);
            String status = textOrNull(node, "status");
            String outputFileId = textOrNull(node, "output_file_id");
            if (status != null && TERMINAL.contains(status)) {
                if (!job.isCostBooked()) {
                    bookCompletion(job, provider, status, outputFileId);
                }
            } else {
                batchJobRepository.updateStatus(job.getId(), job.getWorkspaceId(), status, outputFileId);
            }
        } catch (Exception e) {
            log.warn("Batch status/metering update failed for {} (workspace {}): {}",
                    job.getProviderBatchId(), job.getWorkspaceId(), e.toString());
        }
    }

    // ---- cancel -----------------------------------------------------------

    /**
     * Asks the provider to stop an in-progress batch, and settles what it already ran.
     *
     * <p>Only the workspace that submitted it can cancel it: the job is resolved by
     * {@code (providerBatchId, workspaceId)}, so guessing an id is not enough.
     *
     * <p>The provider's batch object is returned verbatim and settled through the same path a
     * terminal poll uses, because a cancelled batch is billed for the requests that completed
     * before it stopped, and those sit in its output file.
     */
    public String cancelBatch(String providerBatchId, String workspaceId) {
        BatchJob job = requireJob(providerBatchId, workspaceId);
        LlmProvider provider = dispatcher.selectBatchProvider(job.getProvider());
        String raw = provider.cancelBatch(providerBatchId);
        settle(job, provider, raw);
        audit(workspaceId, "BATCH_CANCELLED", Map.of(
                "provider_batch_id", nullSafe(providerBatchId),
                "provider", nullSafe(job.getProvider())));
        return raw;
    }

    // ---- listing ----------------------------------------------------------

    /** Default page size, and the ceiling, both matching the provider API this mirrors. */
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    /**
     * The workspace's batches, newest first, in the provider's own list envelope.
     *
     * <p>Paged from this gateway's tracking rows, not from the provider, so a workspace can only
     * ever see its own batches even when the provider credential is shared. Each row's body is
     * then fetched from the provider verbatim, since a tracking row holds none of the batch
     * object's counts or timestamps; that is one upstream call per row, which is why the page is
     * bounded at {@link #MAX_LIMIT}.
     *
     * <p>It does not settle: settling downloads a batch's whole output file, and a page of them
     * could be gigabytes. Metering stays on the retrieve and cancel paths, where the cost is one
     * batch. A row whose upstream fetch fails is skipped rather than failing the page, so the page
     * may hold fewer entries than asked for.
     *
     * @param after  a provider batch id to page after; entries up to and including it are skipped
     * @param limit  page size, clamped to 1..{@link #MAX_LIMIT}, defaulting to {@link #DEFAULT_LIMIT}
     */
    public String listBatches(String workspaceId, Integer limit, String after) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<BatchJob> all = batchJobRepository.findByWorkspaceId(workspaceId);

        int from = 0;
        if (after != null && !after.isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (after.equals(all.get(i).getProviderBatchId())) {
                    from = i + 1;
                    break;
                }
            }
        }

        List<BatchJob> page = all.subList(Math.min(from, all.size()), Math.min(from + size, all.size()));
        boolean hasMore = from + page.size() < all.size();

        ArrayNode data = JsonMapper.instance().createArrayNode();
        String firstId = null;
        String lastId = null;
        for (BatchJob job : page) {
            String raw;
            try {
                raw = dispatcher.selectBatchProvider(job.getProvider()).getBatch(job.getProviderBatchId());
            } catch (Exception e) {
                log.warn("Batch {} (workspace {}) omitted from the listing: {}",
                        job.getProviderBatchId(), workspaceId, e.toString());
                continue;
            }
            data.add(parse(raw));
            if (firstId == null) {
                firstId = job.getProviderBatchId();
            }
            lastId = job.getProviderBatchId();
        }

        ObjectNode envelope = JsonMapper.instance().createObjectNode();
        envelope.put("object", "list");
        envelope.set("data", data);
        envelope.put("first_id", firstId);
        envelope.put("last_id", lastId);
        envelope.put("has_more", hasMore);
        return envelope.toString();
    }

    // ---- file content -----------------------------------------------------

    /**
     * The raw content of a file this workspace's batches produced or were submitted with.
     *
     * <p>A file id is not workspace-scoped on its own; {@code findByFileIdAndWorkspaceId} supplies
     * the scope, so a file is readable only if a batch in this workspace recorded it. A file that
     * is not this workspace's is reported as not found rather than forbidden, since file ids are
     * guessable and the distinction would reveal which of another workspace's ids exist.
     *
     * <p>It does not settle: downloading an output file is not evidence a batch finished. The
     * status is, and the retrieve path reads it.
     */
    public byte[] getFileContent(String fileId, String workspaceId) {
        BatchJob job = batchJobRepository.findByFileIdAndWorkspaceId(fileId, workspaceId)
                .orElseThrow(() -> new GatewayException("FILE_NOT_FOUND",
                        "No file " + fileId + " for this workspace"));
        return dispatcher.selectBatchProvider(job.getProvider()).getFileContent(fileId);
    }

    // ---- completion booking -----------------------------------------------

    private void bookCompletion(BatchJob job, LlmProvider provider, String status, String outputFileId) {
        if (outputFileId == null || outputFileId.isBlank()) {
            // Terminal with nothing to bill: no output file, so nothing ran. Atomically claim so
            // repeated polls stop re-checking; no usage/cost booked. Only the winner audits.
            //
            // The presence of an output file is the test, not the status label: a cancelled or
            // expired batch is still billed for the requests that finished before it stopped, and
            // those sit in its output file.
            if (batchJobRepository.claimBooking(job.getId(), job.getWorkspaceId(), status, outputFileId)) {
                audit(job.getWorkspaceId(), "BATCH_COMPLETED_NO_USAGE", Map.of(
                        "provider_batch_id", nullSafe(job.getProviderBatchId()), "status", nullSafe(status)));
            }
            return;
        }

        byte[] output = provider.getFileContent(outputFileId);
        Map<String, long[]> perModel = sumUsageByModel(output, job); // model -> [inputTokens, outputTokens]

        // Atomic claim + transactional persist: exactly one concurrent poll wins; a mid-loop failure
        // rolls the claim back so the next poll retries cleanly (no double-book, no partial book).
        BatchCostBooker.BookingResult result = costBooker.book(job, perModel, status, outputFileId);
        if (!result.claimed()) {
            return; // another poll already booked this batch
        }

        // Post-commit side effects: the Prometheus cost counter, usage listeners, and the audit trail.
        for (CostRecord cr : result.costRecords()) {
            metrics.recordCost(job.getWorkspaceId(), cr.getModel(), job.getProvider(),
                    cr.getTotalCost() != null ? cr.getTotalCost().doubleValue() : 0.0);
        }
        usageListeners.forEach(listener -> listener.usageRecorded(job.getWorkspaceId()));
        long totalIn = perModel.values().stream().mapToLong(a -> a[0]).sum();
        long totalOut = perModel.values().stream().mapToLong(a -> a[1]).sum();
        audit(job.getWorkspaceId(), "BATCH_COST_BOOKED", Map.of(
                "provider_batch_id", nullSafe(job.getProviderBatchId()),
                "models", perModel.keySet(),
                "input_tokens", totalIn,
                "output_tokens", totalOut));
    }

    /**
     * Parses a batch output JSONL, summing prompt/completion tokens per model across all lines. Lines
     * that don't parse or carry no usage block are skipped and counted — a non-zero skip count is
     * WARN-logged because it means the batch is <em>under-billed</em> (the workspace is under-charged),
     * so the gap is visible rather than silent.
     */
    private Map<String, long[]> sumUsageByModel(byte[] output, BatchJob job) {
        Map<String, long[]> perModel = new LinkedHashMap<>();
        String text = new String(output, StandardCharsets.UTF_8);
        int lines = 0, skipped = 0;
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            lines++;
            JsonNode body;
            try {
                body = OBJECT_MAPPER.readTree(line).path("response").path("body");
            } catch (Exception e) {
                skipped++;
                continue;
            }
            JsonNode usage = body.path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                skipped++;
                continue;
            }
            String model = body.path("model").asText("unknown");
            long[] acc = perModel.computeIfAbsent(model, k -> new long[2]);
            acc[0] += usage.path("prompt_tokens").asLong(0);
            acc[1] += usage.path("completion_tokens").asLong(0);
        }
        if (skipped > 0) {
            log.warn("Batch {}: {}/{} output lines had no parseable usage block and were not billed "
                    + "(workspace {} under-charged for those requests).",
                    job.getProviderBatchId(), skipped, lines, job.getWorkspaceId());
        }
        return perModel;
    }

    // ---- helpers ----------------------------------------------------------

    private BatchJob requireJob(String providerBatchId, String workspaceId) {
        return batchJobRepository.findByProviderBatchIdAndWorkspaceId(providerBatchId, workspaceId)
                .orElseThrow(() -> new GatewayException("BATCH_NOT_FOUND", "Batch not found: " + providerBatchId));
    }

    private JsonNode parse(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new GatewayException("PROVIDER_ERROR", "Unparseable batch provider response: " + e.getMessage());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    private void audit(String workspaceId, String eventType, Map<String, Object> payload) {
        auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(), workspaceId, eventType, payload));
    }
}