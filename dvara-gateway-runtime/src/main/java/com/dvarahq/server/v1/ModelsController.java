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

import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.ModelListResponse;
import com.dvarahq.server.web.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/v1")
@Tag(name = "Models", description = "Model discovery and capabilities")
public class ModelsController {

    private static final Logger log = LoggerFactory.getLogger(ModelsController.class);

    /** How long one provider's model list may take before it is left out of the answer. */
    static final java.time.Duration PER_PROVIDER_TIMEOUT = java.time.Duration.ofSeconds(5);

    private static final java.util.concurrent.ExecutorService LISTING =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final ProviderDispatcher dispatcher;
    private final java.time.Duration perProviderTimeout;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelsController(ProviderDispatcher dispatcher) {
        this(dispatcher, PER_PROVIDER_TIMEOUT);
    }

    ModelsController(ProviderDispatcher dispatcher, java.time.Duration perProviderTimeout) {
        this.dispatcher = dispatcher;
        this.perProviderTimeout = perProviderTimeout;
    }

    @GetMapping("/models")
    @Operation(summary = "List models", description = "Lists all available models from registered providers by querying each provider's API.")
    @ApiResponse(responseCode = "200", description = "Model list returned")
    public ResponseEntity<ModelListResponse> models(HttpServletRequest httpRequest) {
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);

        // Every provider is asked at once, and one that has not answered within the timeout is left
        // out, so a single slow provider cannot slow this endpoint for every caller.
        List<LlmProvider> providers = dispatcher.allProviders();
        List<java.util.concurrent.Future<List<ModelInfo>>> lists = new ArrayList<>(providers.size());
        for (LlmProvider provider : providers) {
            lists.add(LISTING.submit(provider::listModels));
        }
        long deadline = System.nanoTime() + perProviderTimeout.toNanos();
        List<ModelListResponse.ModelData> models = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            LlmProvider provider = providers.get(i);
            java.util.concurrent.Future<List<ModelInfo>> listing = lists.get(i);
            try {
                ModelListResponse.CapabilitiesDto capsDto = toCapabilitiesDto(provider.capabilities());
                long remaining = Math.max(0, deadline - System.nanoTime());
                List<ModelInfo> providerModels;
                try {
                    providerModels = listing.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    listing.cancel(true);
                    log.warn("Provider {} did not list its models within {} ms; left out of this answer",
                            provider.name(), perProviderTimeout.toMillis());
                    continue;
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
                for (ModelInfo info : providerModels) {
                    models.add(ModelListResponse.ModelData.builder()
                            .id(info.id())
                            .object("model")
                            .created(info.created())
                            .ownedBy(info.ownedBy())
                            .capabilities(capsDto)
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to list models from provider {}: {}", provider.name(), e.getMessage());
            }
        }

        ModelListResponse response = ModelListResponse.builder()
                .object("list")
                .data(models)
                .build();

        return ResponseEntity.ok()
                .header(TraceIdFilter.HEADER, traceId)
                .body(response);
    }

    static ModelListResponse.CapabilitiesDto toCapabilitiesDto(ProviderCapabilities caps) {
        return ModelListResponse.CapabilitiesDto.builder()
                .supportsStreaming(caps.supportsStreaming())
                .supportsVision(caps.supportsVision())
                .supportsToolCalls(caps.supportsToolCalls())
                .supportsStructuredOutputs(caps.supportsStructuredOutputs())
                .supportsJsonMode(caps.supportsJsonMode())
                .maxContextTokens(caps.maxContextTokens())
                .build();
    }
}