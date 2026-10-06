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
package com.dvarahq.autoconfigure.guardrail;

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.core.provider.ModelContextLimits;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Model context windows written in configuration, {@code dvara.llm-gateway.model-limits}.
 *
 * <p>Ordered first among {@link ModelContextLimits} beans: a window the operator writes wins over any
 * other source. Entries are checked when the gateway starts, so a mistyped limit stops startup instead
 * of quietly leaving the provider's window in force.
 *
 * <p>When several entries match, the most specific one wins: an exact model over a {@code *} prefix, a
 * longer prefix over a shorter one, and, between equally specific entries, one naming the provider
 * over one that does not.
 */
public class ConfiguredModelContextLimits implements ModelContextLimits, Ordered {

    private final List<Entry> entries;

    public ConfiguredModelContextLimits(List<GatewayProperties.ModelLimit> limits) {
        List<Entry> checked = new ArrayList<>();
        if (limits != null) {
            for (int i = 0; i < limits.size(); i++) {
                checked.add(Entry.of(limits.get(i), i));
            }
        }
        this.entries = List.copyOf(checked);
    }

    @Override
    public OptionalInt contextTokens(String provider, String model) {
        if (model == null || entries.isEmpty()) {
            return OptionalInt.empty();
        }
        Entry best = null;
        for (Entry entry : entries) {
            if (entry.matches(provider, model) && (best == null || entry.specificity() > best.specificity())) {
                best = entry;
            }
        }
        return best == null ? OptionalInt.empty() : OptionalInt.of(best.contextTokens());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private record Entry(String model, boolean prefix, String provider, int contextTokens) {

        static Entry of(GatewayProperties.ModelLimit limit, int index) {
            String where = "dvara.llm-gateway.model-limits[" + index + "]";
            if (limit == null || limit.getModel() == null || limit.getModel().isBlank()) {
                throw new IllegalStateException(where + ".model is required");
            }
            if (limit.getContextTokens() <= 0) {
                throw new IllegalStateException(where + ".context-tokens must be a positive number of tokens, was "
                        + limit.getContextTokens());
            }
            String model = limit.getModel().strip();
            boolean prefix = model.endsWith("*");
            if (prefix) {
                model = model.substring(0, model.length() - 1);
            }
            if (model.contains("*")) {
                throw new IllegalStateException(where + ".model may have a '*' only at the end: " + limit.getModel());
            }
            String provider = limit.getProvider() == null || limit.getProvider().isBlank()
                    ? null : limit.getProvider().strip().toLowerCase(Locale.ROOT);
            return new Entry(model, prefix, provider, limit.getContextTokens());
        }

        boolean matches(String requestProvider, String requestModel) {
            if (provider != null && (requestProvider == null
                    || !provider.equals(requestProvider.toLowerCase(Locale.ROOT)))) {
                return false;
            }
            return prefix ? requestModel.startsWith(model) : requestModel.equals(model);
        }

        /**
         * Higher is more specific. An exact model outranks every prefix; a longer prefix outranks a
         * shorter one; naming the provider breaks a tie.
         */
        long specificity() {
            long byModel = prefix ? model.length() : Integer.MAX_VALUE;
            return byModel * 2 + (provider != null ? 1 : 0);
        }
    }
}
