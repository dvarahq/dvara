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
package com.dvarahq.autoconfigure.resilience;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.resilience.FallbackResolver;

import java.util.List;
import java.util.stream.Collectors;

public class ConfigurableFallbackResolver implements FallbackResolver {

    @Override
    public List<LlmProvider> resolve(ChatRequest request, LlmProvider failedProvider, List<LlmProvider> allProviders) {
        return allProviders.stream()
                .filter(p -> p.supports(request))
                .filter(p -> !p.name().equals(failedProvider.name()))
                .filter(p -> supportsResponseFormat(p, request.getResponseFormat()))
                .filter(p -> carriesImage(request) ? p.capabilities().supportsVision() : true)
                .collect(Collectors.toList());
    }

    /**
     * Whether any message carries an image, which decides if {@code supportsVision} matters here.
     *
     * <p><b>This is checked on failover and not on the primary route</b>, and the asymmetry is the
     * point. On the primary route the caller named the model, and the vision declarations are
     * deliberately imprecise per provider — ChatGLM declares vision off because only {@code glm-4v-*}
     * has it, Grok declares it on for a family where not every model does — so filtering there would
     * refuse image requests that work today. On a failover the caller chose nothing: the gateway is
     * about to hand their image to a provider they did not ask for, and one that says it cannot take
     * an image is the wrong place to send it. Excluded here, the dispatcher reports
     * {@code FAILOVER_CAPABILITY_MISMATCH} and sets {@code X-Gateway-Failover-Blocked}, which is what
     * those already exist to say.
     */
    private static boolean carriesImage(ChatRequest request) {
        if (request.getMessages() == null) {
            return false;
        }
        return request.getMessages().stream()
                .filter(m -> m.getContent() != null)
                .flatMap(m -> m.getContent().stream())
                .anyMatch(b -> b instanceof ContentBlock.ImageBlock);
    }

    private boolean supportsResponseFormat(LlmProvider provider, ResponseFormat format) {
        if (format == null || format instanceof ResponseFormat.Text) {
            return true;
        }
        if (format instanceof ResponseFormat.JsonSchema) {
            return provider.capabilities().supportsStructuredOutputs();
        }
        if (format instanceof ResponseFormat.JsonObject) {
            return provider.capabilities().supportsJsonMode();
        }
        return true;
    }
}