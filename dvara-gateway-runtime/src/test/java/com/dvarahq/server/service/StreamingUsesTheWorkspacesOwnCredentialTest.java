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
package com.dvarahq.server.service;

import com.dvarahq.autoconfigure.resilience.ResilientLlmProvider;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A streamed call must go upstream on its own workspace's credential.
 *
 * <p><b>Why this test has real parts in it.</b> An earlier version of this fix was covered by two
 * tests that could not fail: one set the workspace by hand and then checked it could be read back,
 * which tests nothing but the holder, and the other ran through a mocked dispatcher, so no provider
 * and no credential lookup were on the path at all. Deleting the fix left both green. So this one
 * puts the pieces that actually matter in the way of the call — a real {@link ResilientLlmProvider},
 * a provider that resolves a credential the way the interceptor does, and a request that has
 * genuinely finished — and asks the only question worth asking: which workspace did we look the
 * credential up for?
 *
 * <p>The request is completed before the call, because that is the situation a stream creates. The
 * controller hands back the emitter, the dispatch unwinds, and only then does the upstream call go
 * out. Anything that tries to ask the request who the caller is at that point gets an exception
 * instead of an answer, which is why streaming failed on every provider carrying a credential.
 */
class StreamingUsesTheWorkspacesOwnCredentialTest {

    private static final String WORKSPACE = "ws-a";

    /** Records which workspace the credential was looked up for, which is the whole question. */
    private final AtomicReference<String> askedFor = new AtomicReference<>("never asked");

    private ChatExecutionService service;
    private ResilientLlmProvider resilientProvider;

    @BeforeEach
    void setUp() {
        // A secret store that remembers who it was asked about. The default two-argument form is
        // what the credential path calls.
        SecretProvider secrets = new SecretProvider() {
            @Override
            public Optional<String> getSecret(String key) {
                return Optional.of("installation-wide-key");
            }

            @Override
            public Optional<String> getSecret(String key, String workspaceId) {
                askedFor.set(workspaceId);
                return Optional.of(workspaceId == null ? "installation-wide-key" : "key-for-" + workspaceId);
            }
        };

        // A provider that does what every credential-bearing provider does at the moment it opens a
        // stream: work out whose call this is, and fetch that workspace's key.
        LlmProvider provider = new LlmProvider() {
            @Override
            public String name() {
                return "test-provider";
            }

            @Override
            public boolean supports(ChatRequest request) {
                return true;
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Iterator<SseChunk> streamChat(ChatRequest request) {
                secrets.getSecret("provider.test.api-key", CredentialInterceptor.resolveWorkspaceId());
                return List.of(SseChunk.builder().id("c").delta("hi").done(true).build()).iterator();
            }

            @Override
            public ProviderCapabilities capabilities() {
                return new ProviderCapabilities(true, false, false, false, false, 128000);
            }
        };

        resilientProvider = new ResilientLlmProvider(provider,
                CircuitBreaker.of("t", CircuitBreakerConfig.custom()
                        .failureRateThreshold(50).slidingWindowSize(4).minimumNumberOfCalls(4)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(1).build()),
                Retry.of("t", RetryConfig.custom()
                        .maxAttempts(1).waitDuration(Duration.ofMillis(1))
                        .retryOnException(e -> e instanceof GatewayException).build()),
                TimeLimiter.of("t-chat", TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(5)).build()),
                TimeLimiter.of("t-stream", TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(5)).build()));

        // The dispatcher is a stand-in, but it hands the call to the real resilience wrapper, which
        // is one of the two places the workspace has to survive.
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        when(dispatcher.streamChat(any()))
                .thenAnswer(inv -> resilientProvider.streamChat(inv.getArgument(0)));

        StreamingResponseEnforcer passThrough = mock(StreamingResponseEnforcer.class);
        when(passThrough.wrap(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        service = new ChatExecutionService(
                dispatcher, mock(RequestPipeline.class), TestProviders.of(mock(ResponseCache.class)),
                mock(TokenUsageRepository.class), TestProviders.of(mock(WorkspaceUsageListener.class)),
                TestProviders.of(mock(CostCalculationService.class)), TestProviders.of(mock(CostEstimator.class)),
                mock(PiiEnforcer.class), mock(RateLimiter.class), passThrough, mock(AuditWriter.class),
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                mock(TokenEstimator.class), TestProviders.of(CallOutcomeListener.NOOP));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A request in the state a stream leaves behind: bound, but finished. */
    private static void bindAFinishedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("workspaceId", WORKSPACE);
        ServletRequestAttributes attrs = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attrs);
        attrs.requestCompleted();
    }

    @Test
    void theStreamGoesOutOnTheWorkspacesOwnKey() {
        bindAFinishedRequest();

        service.openStream(ChatRequest.builder().model("gpt-4o").build(), WORKSPACE);

        assertThat(askedFor.get())
                .describedAs("the credential must be looked up for this workspace, not for nobody — "
                        + "before this fix the lookup threw and the stream never reached the provider")
                .isEqualTo(WORKSPACE);
    }

    /**
     * The consequence of getting it wrong, stated as its own assertion.
     *
     * <p>Resolving to no workspace does not fail loudly. It quietly selects the installation's own
     * credential, so one customer's traffic goes out — and is billed — on a key that is not theirs.
     * That is why the fix carries the value rather than catching the exception and moving on.
     */
    @Test
    void itNeverFallsBackToTheInstallationWideKey() {
        bindAFinishedRequest();

        service.openStream(ChatRequest.builder().model("gpt-4o").build(), WORKSPACE);

        assertThat(askedFor.get())
                .describedAs("a null workspace here means the shared credential was chosen")
                .isNotNull();
    }

    /** A caller with no request at all — a scheduler, a probe — still works and has no workspace. */
    @Test
    void aCallWithNoRequestBehindItStillRuns() {
        RequestContextHolder.resetRequestAttributes();

        service.openStream(ChatRequest.builder().model("gpt-4o").build(), null);

        assertThat(askedFor.get()).isNull();
    }
}
