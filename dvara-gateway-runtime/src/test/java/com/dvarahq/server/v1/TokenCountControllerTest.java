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

import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.cost.TokenPrices;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.guardrail.ContextThresholds;
import com.dvarahq.core.guardrail.ContextWindowGovernor;
import com.dvarahq.core.guardrail.TokenBreakdown;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.server.filter.ContextWindowFilter;
import com.dvarahq.server.service.ChatExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The answer's arithmetic: cost from the prices, the window's use, and what is said when a part is unknown. */
class TokenCountControllerTest {

    private final ChatExecutionService execution = mock(ChatExecutionService.class);
    private final TokenEstimator estimator = mock(TokenEstimator.class);
    private final CostEstimator costs = mock(CostEstimator.class);
    private final ContextWindowFilter window = mock(ContextWindowFilter.class);
    private final ContextWindowGovernor governor = mock(ContextWindowGovernor.class);
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    private TokenCountController controller;

    @BeforeEach
    void setUp() {
        http.setAttribute("workspaceId", "w1");
        when(estimator.breakdown(any())).thenReturn(Optional.of(
                new TokenBreakdown(10, 20, 0, 5, 0, 0, 7, List.of(14, 24), "o200k_base", true)));
        when(window.windowFor(any())).thenReturn(1_000);
        when(governor.thresholds("w1")).thenReturn(new ContextThresholds(50, 80));
        when(execution.countInput(any(), any(), any())).thenAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            return new ChatExecutionService.InputCount(42, false, "openai", r.getModel(), r);
        });
        controller = new TokenCountController(execution, estimator, provider(costs), provider(window),
                provider(governor));
    }

    @Test
    void costIsTheCountAndTheReservedOutputAtTheModelsPrices() {
        when(costs.prices("gpt-4o", "openai")).thenReturn(Optional.of(
                new TokenPrices(new BigDecimal("2.50"), new BigDecimal("10.00"))));

        Map<String, Object> c = only(controller.count(body("{\"model\": \"gpt-4o\", \"max_tokens\": 1000, "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http));

        assertThat(c.get("pricing_known")).isEqualTo(true);
        // 42 x 2.50 / 1M and 1000 x 10.00 / 1M
        assertThat((BigDecimal) c.get("input_cost_usd")).isEqualByComparingTo("0.000105");
        assertThat((BigDecimal) c.get("max_output_cost_usd")).isEqualByComparingTo("0.01");
        assertThat((BigDecimal) c.get("max_total_cost_usd")).isEqualByComparingTo("0.010105");
    }

    @Test
    void aModelWithNoPrice_orAPriceSourceThatFails_isUnpriced_notFree() {
        when(costs.prices(eq("gpt-4o"), any())).thenReturn(Optional.empty());
        when(costs.prices(eq("gpt-4o-mini"), any())).thenThrow(new IllegalStateException("store down"));

        for (String model : List.of("gpt-4o", "gpt-4o-mini")) {
            Map<String, Object> c = only(controller.count(body("{\"model\": \"" + model + "\", "
                    + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http));
            assertThat(c.get("pricing_known")).isEqualTo(false);
            assertThat(c).doesNotContainKeys("input_cost_usd", "max_output_cost_usd", "max_total_cost_usd");
        }
    }

    @Test
    void theWindowsUseIsTheInputAgainstTheWorkspacesThresholds_andTheReservedOutputDecidesTheOverflow() {
        Map<String, Object> c = only(controller.count(body("{\"model\": \"gpt-4o\", \"max_tokens\": 958, "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http));

        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) c.get("context");
        assertThat(context.get("window_tokens")).isEqualTo(1_000);
        assertThat(context.get("utilization_pct")).isEqualTo(4.2);
        assertThat(context.get("warning_threshold_pct")).isEqualTo(50);
        assertThat(context.get("over_warning_threshold")).isEqualTo(false);
        assertThat(context.get("overflows")).as("42 + 958 is exactly the window").isEqualTo(false);

        Map<String, Object> over = only(controller.count(body("{\"model\": \"gpt-4o\", \"max_tokens\": 959, "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http));
        @SuppressWarnings("unchecked")
        Map<String, Object> overContext = (Map<String, Object>) over.get("context");
        assertThat(overContext.get("overflows")).isEqualTo(true);
    }

    @Test
    void theBreakdownIsTheEstimatorsParts_andTheVocabularyIsNamed() {
        Map<String, Object> c = only(controller.count(body("{\"model\": \"gpt-4o\", "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http));

        assertThat(c.get("tokenizer")).isEqualTo(Map.of("encoding", "o200k_base", "exact", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> parts = (Map<String, Object>) c.get("breakdown");
        assertThat(parts).containsEntry("system", 10).containsEntry("user", 20).containsEntry("tools", 5)
                .containsEntry("overhead", 7).containsEntry("total", 42).containsEntry("per_message", List.of(14, 24));
    }

    @Test
    void oneModelsRefusalIsTheAnswer_butInAComparisonEachModelAnswersForItself() {
        doAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            if (r.getModel().equals("nope")) {
                throw new GatewayException("NO_PROVIDER", "No provider configured for model: nope");
            }
            return new ChatExecutionService.InputCount(42, true, "anthropic", r.getModel(), r);
        }).when(execution).countInput(any(), any(), any());

        assertThatThrownBy(() -> controller.count(body("{\"model\": \"nope\", "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http))
                .isInstanceOf(GatewayException.class);

        Map<String, Object> out = controller.count(body("{\"models\": [\"claude-x\", \"nope\"], "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> counts = (List<Map<String, Object>>) out.get("counts");
        assertThat(counts.get(0)).containsEntry("counted_by", "provider").containsEntry("input_tokens", 42);
        assertThat(counts.get(1).get("error")).isEqualTo(Map.of("code", "no_provider",
                "message", "No provider configured for model: nope"));

        // Every model refused: the first refusal is the answer.
        assertThatThrownBy(() -> controller.count(body("{\"models\": [\"nope\"], "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void withNoWindowSource_orNoBreakdown_thoseFieldsAreLeftOut() {
        doAnswer(inv -> Optional.empty()).when(estimator).breakdown(any());
        TokenCountController bare = new TokenCountController(execution, estimator, provider(null), provider(null),
                provider(null));

        Map<String, Object> c = only(bare.count(body("{\"model\": \"gpt-4o\", "
                + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"), http));

        assertThat(c).doesNotContainKeys("breakdown", "tokenizer", "context");
        assertThat(c.get("pricing_known")).isEqualTo(false);
        assertThat(c.get("input_tokens")).isEqualTo(42);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> only(Map<String, Object> out) {
        List<Map<String, Object>> counts = (List<Map<String, Object>>) out.get("counts");
        assertThat(counts).hasSize(1);
        return counts.getFirst();
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }
}
