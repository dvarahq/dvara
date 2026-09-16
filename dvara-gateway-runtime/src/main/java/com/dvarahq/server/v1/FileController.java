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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * OpenAI-compatible Files endpoint for the Batch API surface. Governed passthrough: the
 * uploaded JSONL is PII-scanned as a unit (per the workspace's policy) before it is relayed to the
 * provider's Files API. The provider response (carrying the file id a batch references) is returned
 * verbatim.
 *
 * <p>Size is capped by {@code spring.servlet.multipart.max-file-size} (100 MB); an oversized upload
 * is turned into a clean {@code 413} rather than Tomcat's bare error.</p>
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Batch", description = "Batch API — file upload + jobs")
public class FileController {

    private final BatchExecutionService batchService;

    public FileController(BatchExecutionService batchService) {
        this.batchService = batchService;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a batch input file",
               description = "Uploads a JSONL file (governed: PII-scanned before forwarding) to the provider's Files API.")
    public ResponseEntity<String> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "purpose", defaultValue = "batch") String purpose,
            @RequestParam(value = "provider", required = false) String provider,
            HttpServletRequest httpRequest) throws IOException {

        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        String raw = batchService.uploadFile(file.getBytes(), file.getOriginalFilename(), purpose, workspaceId, provider);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(raw);
    }
    @GetMapping(value = "/files/{fileId}/content")
    @Operation(summary = "Download a batch file",
               description = "The raw content of a file one of this workspace's batches produced or was submitted "
                       + "with. A file belonging to another workspace is reported as not found, because file ids "
                       + "are guessable and saying otherwise would reveal which of them exist.")
    public ResponseEntity<byte[]> fileContent(@PathVariable String fileId, HttpServletRequest httpRequest) {
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(batchService.getFileContent(fileId, workspaceId));
    }

    // Oversized-upload (413) is handled centrally by GlobalExceptionHandler — the multipart
    // exception is thrown during request resolution, before a controller handler is selected, so a
    // controller-local @ExceptionHandler would never fire.
}