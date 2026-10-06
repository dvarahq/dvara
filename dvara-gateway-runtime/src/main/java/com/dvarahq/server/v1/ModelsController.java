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
import com.dvarahq.core.provider.ModelContextLimits;
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ModelListFilter;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.server.filter.ModelWindows;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.AnthropicModelListResponse;
import com.dvarahq.server.v1.dto.ModelListResponse;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import com.dvarahq.server.web.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * {@code GET /v1/models}: the models of every configured provider, narrowed to what the caller may use by
 * any registered {@link ModelListFilter}, each with its own context window where a
 * {@link ModelContextLimits} source knows it.
 *
 * <p>A request with an {@code anthropic-version} header, as an Anthropic client sends, is answered in
 * Anthropic's model-list shape and lists only Claude models served by Anthropic. Other models are left
 * out rather than shown under Claude-like names, so a client built for Anthropic never offers a model it
 * does not expect.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Models", description = "Model discovery and capabilities")
public class ModelsController {

    private static final Logger log = LoggerFactory.getLogger(ModelsController.class);

    /** How long one provider's model list may take before it is left out of the answer. */
    static final java.time.Duration PER_PROVIDER_TIMEOUT = java.time.Duration.ofSeconds(5);

    /** The header an Anthropic client sends on every request. */
    static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";

    private static final java.util.concurrent.ExecutorService LISTING =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final ProviderDispatcher dispatcher;
    private final java.time.Duration perProviderTimeout;
    private final List<ModelListFilter> listFilters;
    private final List<ModelContextLimits> modelLimits;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelsController(ProviderDispatcher dispatcher,
                            ObjectProvider<ModelListFilter> listFilters,
                            ObjectProvider<ModelContextLimits> modelLimits) {
        this(dispatcher, PER_PROVIDER_TIMEOUT,
                listFilters.orderedStream().toList(), modelLimits.orderedStream().toList());
    }

    /** With no list filter and no per-model windows: every model, with its provider's window. */
    public ModelsController(ProviderDispatcher dispatcher) {
        this(dispatcher, PER_PROVIDER_TIMEOUT, List.of(), List.of());
    }

    ModelsController(ProviderDispatcher dispatcher, java.time.Duration perProviderTimeout) {
        this(dispatcher, perProviderTimeout, List.of(), List.of());
    }

    ModelsController(ProviderDispatcher dispatcher, java.time.Duration perProviderTimeout,
                     List<ModelListFilter> listFilters, List<ModelContextLimits> modelLimits) {
        this.dispatcher = dispatcher;
        this.perProviderTimeout = perProviderTimeout;
        this.listFilters = listFilters;
        this.modelLimits = modelLimits;
    }

    /** One listed model and the provider that listed it. */
    private record Listed(LlmProvider provider, ModelInfo info) {
    }

    @GetMapping("/models")
    @Operation(summary = "List models", description = "Lists the models of the registered providers that the "
            + "caller may use, each with its own context window where one is known. Answers in Anthropic's "
            + "shape, with Claude models only, when the request carries an anthropic-version header.")
    @ApiResponse(responseCode = "200", description = "Model list returned")
    public ResponseEntity<?> models(HttpServletRequest httpRequest) {
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        Object workspace = httpRequest.getAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR);
        List<Listed> listed = narrow(workspace instanceof String w ? w : null, listAll());

        Object body = httpRequest.getHeader(ANTHROPIC_VERSION_HEADER) != null
                ? anthropicList(listed)
                : openAiList(listed);
        return ResponseEntity.ok()
                .header(TraceIdFilter.HEADER, traceId)
                .body(body);
    }

    /** Every provider's models, in provider order. */
    private List<Listed> listAll() {
        // Every provider is asked at once, and one that has not answered within the timeout is left
        // out, so a single slow provider cannot slow this endpoint for every caller.
        List<LlmProvider> providers = dispatcher.allProviders();
        List<java.util.concurrent.Future<List<ModelInfo>>> lists = new ArrayList<>(providers.size());
        for (LlmProvider provider : providers) {
            lists.add(LISTING.submit(provider::listModels));
        }
        long deadline = System.nanoTime() + perProviderTimeout.toNanos();
        List<Listed> all = new ArrayList<>();
        for (int i = 0; i < providers.size(); i++) {
            LlmProvider provider = providers.get(i);
            java.util.concurrent.Future<List<ModelInfo>> listing = lists.get(i);
            try {
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
                    all.add(new Listed(provider, info));
                }
            } catch (Exception e) {
                log.warn("Failed to list models from provider {}: {}", provider.name(), e.getMessage());
            }
        }
        return all;
    }

    /**
     * What every {@link ModelListFilter} keeps, in the order the providers listed it. An entry a filter
     * returns that it was not given is ignored, so a filter can only remove.
     */
    private List<Listed> narrow(String workspaceId, List<Listed> all) {
        if (listFilters.isEmpty() || all.isEmpty()) {
            return all;
        }
        List<ModelInfo> kept = all.stream().map(Listed::info).toList();
        for (ModelListFilter filter : listFilters) {
            List<ModelInfo> next;
            try {
                next = filter.filter(workspaceId, kept);
            } catch (RuntimeException e) {
                log.warn("Model list filter {} failed; the list is answered without it: {}",
                        filter.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            if (next == null) {
                log.warn("Model list filter {} returned no list; the list is answered without it",
                        filter.getClass().getSimpleName());
                continue;
            }
            kept = next;
        }
        Map<ModelInfo, Integer> remaining = new HashMap<>();
        for (ModelInfo info : kept) {
            remaining.merge(info, 1, Integer::sum);
        }
        List<Listed> out = new ArrayList<>(Math.min(all.size(), kept.size()));
        for (Listed entry : all) {
            Integer count = remaining.get(entry.info());
            if (count != null && count > 0) {
                remaining.put(entry.info(), count - 1);
                out.add(entry);
            }
        }
        return out;
    }

    private ModelListResponse openAiList(List<Listed> listed) {
        Map<LlmProvider, ModelListResponse.CapabilitiesDto> byProvider = new HashMap<>();
        List<ModelListResponse.ModelData> models = new ArrayList<>(listed.size());
        for (Listed entry : listed) {
            ModelListResponse.CapabilitiesDto providerCaps = byProvider.computeIfAbsent(entry.provider(),
                    p -> toCapabilitiesDto(p.capabilities()));
            OptionalInt own = ModelWindows.of(modelLimits, entry.provider().name(), entry.info().id());
            ModelListResponse.CapabilitiesDto caps = own.isPresent()
                    ? providerCaps.toBuilder().maxContextTokens(own.getAsInt()).build()
                    : providerCaps;
            models.add(ModelListResponse.ModelData.builder()
                    .id(entry.info().id())
                    .object("model")
                    .created(entry.info().created())
                    .ownedBy(entry.info().ownedBy())
                    .capabilities(caps)
                    .build());
        }
        return ModelListResponse.builder()
                .object("list")
                .data(models)
                .build();
    }

    /** Anthropic's shape, holding only the Claude models Anthropic serves. The whole list is one page. */
    private static AnthropicModelListResponse anthropicList(List<Listed> listed) {
        List<AnthropicModelListResponse.Model> models = new ArrayList<>();
        for (Listed entry : listed) {
            if (!isClaude(entry.info())) {
                continue;
            }
            models.add(new AnthropicModelListResponse.Model(
                    "model",
                    entry.info().id(),
                    entry.info().id(),
                    Instant.ofEpochSecond(Math.max(0, entry.info().created())).toString()));
        }
        return new AnthropicModelListResponse(
                models,
                false,
                models.isEmpty() ? null : models.getFirst().id(),
                models.isEmpty() ? null : models.getLast().id());
    }

    /** A model Anthropic owns, under a Claude model id. */
    static boolean isClaude(ModelInfo info) {
        return info.id() != null
                && info.id().toLowerCase(Locale.ROOT).startsWith("claude")
                && "anthropic".equalsIgnoreCase(info.ownedBy());
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
