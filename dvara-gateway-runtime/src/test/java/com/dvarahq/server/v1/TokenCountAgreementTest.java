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

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailMetricsListener;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.RateLimitResult;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.policy.guardrail.DefaultContextWindowGovernor;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import com.dvarahq.policy.guardrail.GuardrailScanService;
import com.dvarahq.policy.guardrail.SystemPromptLeakDetector;
import com.dvarahq.policy.guardrail.TiktokenEstimator;
import com.dvarahq.server.v1.dto.ChatCompletionRequest;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import com.dvarahq.server.web.RateLimitServletFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rate limiter, the guardrail's input cap and the context-window check count one request the same
 * way: with the gateway's {@link TokenEstimator}, over the request the endpoint builds. The usage meter
 * estimates an unreported stream with the same estimator and the same request.
 *
 * <p>The request has text, a tool call, a tool definition and an inline image, the parts a
 * character count of the raw body or of the text alone would get wrong.
 */
class TokenCountAgreementTest {

    private static final String BODY = """
            {"model":"gpt-4o",
             "messages":[
               {"role":"system","content":"You answer questions about orders."},
               {"role":"user","content":[
                 {"type":"text","text":"Where is order 1234, and what is in this photo?"},
                 {"type":"image_url","image_url":{"url":"data:image/png;base64,%s","detail":"low"}}]},
               {"role":"assistant","content":null,"tool_calls":[
                 {"id":"call_1","type":"function","function":{"name":"lookup_order","arguments":"{\\"id\\":\\"1234\\"}"}}]},
               {"role":"tool","tool_call_id":"call_1","content":"Shipped on Monday, arriving Thursday."}],
             "tools":[{"type":"function","function":{"name":"lookup_order","description":"Finds an order",
               "parameters":{"type":"object","properties":{"id":{"type":"string"}}}}}]}
            """.formatted("iVBORw0KGgo".repeat(2_000));

    private final TokenEstimator estimator = new TiktokenEstimator();

    @Test
    void theLimiterTheInputCapAndTheContextCheckCountTheSameNumber() throws Exception {
        ChatRequest request = ChatCompletionController.toInternal(
                JsonMapper.instance().readValue(BODY, ChatCompletionRequest.class));
        int count = estimator.estimateTokens(request);
        assertThat(count).as("text, the tool call and the tool definition all count").isGreaterThan(40);

        assertThat(reservedByTheRateLimiter()).as("rate limiter").isEqualTo(count);

        assertThat(new DefaultContextWindowGovernor(estimator, mock(AuditWriter.class), noWorkspaces())
                .evaluate(request, 1_000_000, "w1").estimatedTokens())
                .as("context-window check").isEqualTo(count);

        assertThatNoException().as("input cap at the count")
                .isThrownBy(() -> inputCap(count).enforceRequest(request, "w1"));
        assertThatThrownBy(() -> inputCap(count - 1).enforceRequest(request, "w1"))
                .as("input cap one below the count")
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("~" + count + " > " + (count - 1));
    }

    private int reservedByTheRateLimiter() throws Exception {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.checkLimit(eq("key"), anyInt(), any(EffectiveRateLimit.class))).thenReturn(RateLimitResult.allow());
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/v1/chat/completions");
        http.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        http.setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, "key");

        new RateLimitServletFilter(limiter, estimator, null)
                .doFilter(http, mock(HttpServletResponse.class), mock(FilterChain.class));

        return (Integer) http.getAttribute(RateLimitServletFilter.RESERVED_TOKENS_ATTR);
    }

    private GuardrailScanService inputCap(int maxInputTokens) {
        GuardrailProperties props = new GuardrailProperties();
        props.setEnabled(true);
        props.setDefaultAction(GuardrailAction.BLOCK);
        props.setMaxInputTokens(maxInputTokens);
        GuardrailDetector detector = mock(GuardrailDetector.class);
        when(detector.scanRequest(any(), any())).thenReturn(List.of(GuardrailScanResult.EMPTY));
        return new GuardrailScanService(detector, mock(AuditWriter.class), noWorkspaces(), props,
                new SystemPromptLeakDetector(),
                new StaticListableBeanFactory().getBeanProvider(GuardrailMetricsListener.class), estimator);
    }

    private static WorkspaceRepository noWorkspaces() {
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.empty());
        return workspaces;
    }
}
