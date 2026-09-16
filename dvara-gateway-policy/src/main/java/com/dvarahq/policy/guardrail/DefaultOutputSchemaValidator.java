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
package com.dvarahq.policy.guardrail;

import com.dvarahq.core.guardrail.OutputSchemaConfig;
import com.dvarahq.core.guardrail.OutputSchemaRepository;
import com.dvarahq.core.guardrail.OutputSchemaResult;
import com.dvarahq.core.guardrail.OutputSchemaValidator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.util.JsonMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
// NB: networknt 3.x validation results are com.networknt.schema.Error — referenced by
// fully-qualified name below to avoid shadowing java.lang.Error in this file.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Output schema validator that validates chat responses against
 * JSON schemas configured per route/model pattern using networknt/json-schema-validator.
 */
public class DefaultOutputSchemaValidator implements OutputSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultOutputSchemaValidator.class);
    private final OutputSchemaRepository schemaRepository;

    /**
     * Compiled schemas, by config id, with the body they were compiled from.
     *
     * <p>Keyed by id alone, an edited schema would never be recompiled: the repository serves the
     * new body on the next request, and this map would hand back the copy compiled at first use.
     * Holding the body makes the comparison possible. Not a {@code TtlLruCache}: a TTL would
     * recompile on a timer whether or not anything changed and still serve a stale schema until
     * it expired. One entry per registered schema is bounded by the registry, not by traffic.</p>
     */
    private final ConcurrentHashMap<String, CompiledSchema> compiledSchemas = new ConcurrentHashMap<>();

    /** A compiled schema and the exact JSON it was compiled from. */
    private record CompiledSchema(String schemaJson, Schema schema) {
    }

    public DefaultOutputSchemaValidator(OutputSchemaRepository schemaRepository) {
        this.schemaRepository = schemaRepository;
    }

    @Override
    public OutputSchemaResult validate(ChatRequest request, ChatResponse response,
                                        String workspaceId, String routeId) {
        // Find matching schema config
        OutputSchemaConfig config = resolveSchema(request.getModel(), routeId);
        if (config == null || !config.isEnabled()) {
            return OutputSchemaResult.VALID;
        }

        // Extract response text
        String responseText = extractResponseText(response);
        if (responseText == null || responseText.isBlank()) {
            return OutputSchemaResult.VALID;
        }

        try {
            // Reject non-JSON early with a clear message (networknt parses internally,
            // but we keep the explicit "not valid JSON" result for the caller).
            JsonMapper.instance().readTree(responseText);

            // Validate against schema. networknt 3.x runs on Jackson 3 while this module
            // is on Jackson 2 — so pass the raw JSON string and let networknt parse it,
            // never bridging Jackson 2 <-> 3 node types.
            Schema schema = getOrCompileSchema(config);
            List<com.networknt.schema.Error> errors = schema.validate(responseText, InputFormat.JSON);

            if (errors.isEmpty()) {
                return OutputSchemaResult.VALID;
            }

            List<String> errorMessages = errors.stream()
                    .map(com.networknt.schema.Error::getMessage)
                    .toList();

            return OutputSchemaResult.invalid(errorMessages, config.getId());

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return OutputSchemaResult.invalid(
                    List.of("Response is not valid JSON: " + e.getMessage()),
                    config.getId());
        } catch (Exception e) {
            log.error("Schema validation error for model {}: {}", request.getModel(), e.getMessage());
            return OutputSchemaResult.invalid(
                    List.of("Schema validation error: " + e.getMessage()),
                    config.getId());
        }
    }

    /**
     * Resolves the registered schema (if any) that applies to a request.
     *
     * <p>Each {@code OutputSchemaConfig} declares a scope via {@code routeId}
     * and/or {@code modelPattern}. The matching matrix:
     *
     * <ul>
     *   <li>{@code routeId} only — matches when the request's matched route
     *       id equals {@code config.routeId}, regardless of model.</li>
     *   <li>{@code modelPattern} only — matches when the glob fits the
     *       request's model, regardless of route.</li>
     *   <li>Both set — both must match.</li>
     *   <li>Neither set — never matches. Configs in this state are rejected
     *       at create time (see {@code OutputSchemaController}); this branch
     *       is defensive against legacy rows.</li>
     * </ul>
     *
     * <p>When multiple configs match, the most-specific wins (both-axis
     * config beats single-axis). On a tie within the same specificity tier,
     * declaration order from {@code findAll()} decides — first match wins.
     */
    OutputSchemaConfig resolveSchema(String model, String routeId) {
        OutputSchemaConfig bothAxisMatch = null;
        OutputSchemaConfig singleAxisMatch = null;

        for (OutputSchemaConfig config : schemaRepository.findAll()) {
            boolean hasRoute = config.getRouteId() != null;
            boolean hasModel = config.getModelPattern() != null;
            if (!hasRoute && !hasModel) continue;

            boolean routeMatches = !hasRoute
                    || (routeId != null && routeId.equals(config.getRouteId()));
            boolean modelMatches = !hasModel
                    || (model != null && matchesGlob(config.getModelPattern(), model));

            if (!routeMatches || !modelMatches) continue;

            if (hasRoute && hasModel) {
                if (bothAxisMatch == null) bothAxisMatch = config;
            } else {
                if (singleAxisMatch == null) singleAxisMatch = config;
            }
        }

        return bothAxisMatch != null ? bothAxisMatch : singleAxisMatch;
    }

    private Schema getOrCompileSchema(OutputSchemaConfig config) {
        String schemaJson;
        try {
            schemaJson = JsonMapper.instance().writeValueAsString(config.getSchema());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON schema: " + e.getMessage(), e);
        }
        CompiledSchema cached = compiledSchemas.get(config.getId());
        if (cached != null && cached.schemaJson().equals(schemaJson)) {
            return cached.schema();
        }
        // A different body under the same id is an edited schema: compile it and replace the entry.
        CompiledSchema compiled = compile(config.getId(), schemaJson);
        compiledSchemas.put(config.getId(), compiled);
        return compiled.schema();
    }

    private CompiledSchema compile(String id, String schemaJson) {
        try {
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
            return new CompiledSchema(schemaJson, registry.getSchema(schemaJson, InputFormat.JSON));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile JSON schema " + id + ": " + e.getMessage(), e);
        }
    }

    private String extractResponseText(ChatResponse response) {
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }
        var firstChoice = response.getChoices().get(0);
        if (firstChoice.getMessage() == null || firstChoice.getMessage().getContent() == null) {
            return null;
        }
        return firstChoice.getMessage().getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .findFirst()
                .orElse(null);
    }

    static boolean matchesGlob(String pattern, String text) {
        String regex = pattern.replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return text.matches(regex);
    }
}