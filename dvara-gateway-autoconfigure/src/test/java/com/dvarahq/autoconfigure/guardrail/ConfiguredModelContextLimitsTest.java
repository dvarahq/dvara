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
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredModelContextLimitsTest {

    private static GatewayProperties.ModelLimit limit(String model, String provider, int contextTokens) {
        GatewayProperties.ModelLimit limit = new GatewayProperties.ModelLimit();
        limit.setModel(model);
        limit.setProvider(provider);
        limit.setContextTokens(contextTokens);
        return limit;
    }

    private static ConfiguredModelContextLimits of(GatewayProperties.ModelLimit... limits) {
        return new ConfiguredModelContextLimits(List.of(limits));
    }

    @Test
    void anExactModelIsKnownOnAnyProviderWhenNoProviderIsNamed() {
        var limits = of(limit("gpt-4.1", null, 1_047_576));
        assertThat(limits.contextTokens("openai", "gpt-4.1")).hasValue(1_047_576);
        assertThat(limits.contextTokens("azure-openai", "gpt-4.1")).hasValue(1_047_576);
    }

    @Test
    void aModelNotWrittenIsNotKnown() {
        var limits = of(limit("gpt-4.1", null, 1_047_576));
        assertThat(limits.contextTokens("openai", "gpt-4o")).isEmpty();
        assertThat(limits.contextTokens("openai", "gpt-4.1-mini"))
                .as("without a trailing *, the entry is an exact model")
                .isEmpty();
        assertThat(of().contextTokens("openai", "gpt-4.1")).isEmpty();
    }

    @Test
    void anEntryNamingAProviderAppliesOnlyToThatProvider() {
        var limits = of(limit("claude-sonnet-5-5", "anthropic", 1_000_000));
        assertThat(limits.contextTokens("anthropic", "claude-sonnet-5-5")).hasValue(1_000_000);
        assertThat(limits.contextTokens("ANTHROPIC", "claude-sonnet-5-5")).hasValue(1_000_000);
        assertThat(limits.contextTokens("bedrock", "claude-sonnet-5-5")).isEmpty();
    }

    @Test
    void aTrailingStarMatchesAPrefix() {
        var limits = of(limit("gpt-4.1*", null, 1_047_576));
        assertThat(limits.contextTokens("openai", "gpt-4.1-mini")).hasValue(1_047_576);
        assertThat(limits.contextTokens("openai", "gpt-4.1")).hasValue(1_047_576);
        assertThat(limits.contextTokens("openai", "gpt-4o")).isEmpty();
    }

    @Test
    void theMostSpecificEntryWins() {
        var limits = of(
                limit("gpt-*", null, 128_000),
                limit("gpt-4.1*", null, 1_047_576),
                limit("gpt-4.1-nano", null, 500_000),
                limit("gpt-4.1-nano", "azure-openai", 64_000));
        assertThat(limits.contextTokens("openai", "gpt-4o")).hasValue(128_000);
        assertThat(limits.contextTokens("openai", "gpt-4.1-mini"))
                .as("a longer prefix beats a shorter one")
                .hasValue(1_047_576);
        assertThat(limits.contextTokens("openai", "gpt-4.1-nano"))
                .as("an exact model beats any prefix")
                .hasValue(500_000);
        assertThat(limits.contextTokens("azure-openai", "gpt-4.1-nano"))
                .as("naming the provider breaks a tie")
                .hasValue(64_000);
    }

    @Test
    void itIsOrderedFirstSoConfigurationWinsOverOtherSources() {
        assertThat(of().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void anEntryWithNoModelStopsStartupAndSaysWhich() {
        assertThatThrownBy(() -> of(limit("gpt-4.1", null, 1_000), limit(" ", null, 1_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dvara.llm-gateway.model-limits[1].model is required");
    }

    @Test
    void anEntryWithNoPositiveWindowStopsStartupAndSaysWhich() {
        assertThatThrownBy(() -> of(limit("gpt-4.1", null, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-limits[0].context-tokens must be a positive number");
    }

    @Test
    void aStarAnywhereButTheEndStopsStartup() {
        assertThatThrownBy(() -> of(limit("gpt-*-mini", null, 1_000)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only at the end");
    }

    @Test
    void aNullModelInTheRequestIsNotKnown() {
        assertThat(of(limit("gpt-*", null, 1_000)).contextTokens("openai", null)).isEqualTo(OptionalInt.empty());
    }
}
