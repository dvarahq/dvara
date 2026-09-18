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

import com.dvarahq.server.service.BatchExecutionService;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI-compatible Batches endpoint for the Batch API surface. Governed passthrough:
 * submit is budget-gated and tracked; the completion poll ({@code GET /{id}}) is where usage + cost
 * are booked exactly once. Request/response bodies are relayed verbatim.
 *
 * <p>An optional {@code ?provider=} pins which batch-capable provider handles the whole lifecycle;
 * omitted, the first registered batch-capable provider is used (and captured on the tracking row so
 * poll/results stay on the same one).</p>
 */
@RestController
@RequestMapping("/v1/batches")
@Tag(name = "Batch", description = "Batch API — file upload + jobs")
public class BatchController {

    private final BatchExecutionService batchService;

    public BatchController(BatchExecutionService batchService) {
        this.batchService = batchService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Submit a batch job",
               description = "Forwards a batch submit and persists a tracking row. A build that can refuse a submit — a budget cap, say — checks first.")
    public ResponseEntity<String> create(@RequestBody String requestJson,
                                         @RequestParam(value = "provider", required = false) String provider,
                                         HttpServletRequest httpRequest) {
        String workspaceId = ApiKeyAuthFilter.requiredWorkspaceId(httpRequest);
        // The key's opaque id, never the bearer token: the job row persists it and the cost booker
        // copies it into the usage and cost rows at completion.
        String apiKeyId = ApiKeyAuthFilter.requiredApiKeyId(httpRequest);
        String raw = batchService.createBatch(requestJson, workspaceId, apiKeyId, provider);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(raw);
    }

    @GetMapping
    @Operation(summary = "List this workspace's batches",
               description = "Newest first. Listed from the gateway's own tracking rows — never the provider's, "
                       + "which on a shared credential would be every workspace's work — and each entry's body is "
                       + "then fetched from the provider, so one upstream call is made per entry on the page.")
    public ResponseEntity<String> list(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "after", required = false) String after,
            HttpServletRequest httpRequest) {
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(batchService.listBatches(workspaceId, limit, after));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Poll a batch job",
               description = "Returns the batch status; books usage + cost once when the job completes.")
    public ResponseEntity<String> get(@PathVariable String id, HttpServletRequest httpRequest) {
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(batchService.getBatch(id, workspaceId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a batch job",
               description = "Asks the provider to stop an in-progress batch. What already ran is still billed — "
                       + "the provider charges for the requests that completed and leaves them in the output file — "
                       + "so the cancelled batch is settled the same way a terminal poll settles one.")
    public ResponseEntity<String> cancel(@PathVariable String id, HttpServletRequest httpRequest) {
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(batchService.cancelBatch(id, workspaceId));
    }
}