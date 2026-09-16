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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public class ResilientLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmProvider.class);

    /**
     * Upstream provider calls are blocking HTTP that can run for seconds, so they must not run on
     * {@link java.util.concurrent.ForkJoinPool#commonPool()}, which is sized for CPU-bound work and
     * does not compensate for blocked workers: concurrent upstream calls on a small pod would
     * serialise through one thread. A virtual thread per call is the right shape for pure blocking
     * I/O, and the gateway already runs on them ({@code spring.threads.virtual.enabled=true}).
     *
     * <p>A call is submitted rather than run through {@code CompletableFuture.supplyAsync}: when the
     * time limiter gives up it cancels the future with interruption, and only a future from
     * {@code submit} passes that interrupt to the thread. A {@code CompletableFuture} ignores it, and
     * a timed-out call would go on retrying upstream after its caller had been told it failed.
     */
    private static final ExecutorService CALL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final LlmProvider delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final TimeLimiter chatTimeLimiter;
    private final TimeLimiter streamingTimeLimiter;

    public ResilientLlmProvider(LlmProvider delegate,
                                CircuitBreaker circuitBreaker,
                                Retry retry,
                                TimeLimiter chatTimeLimiter,
                                TimeLimiter streamingTimeLimiter) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.chatTimeLimiter = chatTimeLimiter;
        this.streamingTimeLimiter = streamingTimeLimiter;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean supports(ChatRequest request) {
        return delegate.supports(request);
    }

    @Override
    public boolean supportsEmbedding(String model) {
        return delegate.supportsEmbedding(model);
    }

    @Override
    public ProviderCapabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return executeWithResilience(() -> delegate.chat(request), chatTimeLimiter);
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        return executeWithResilience(() -> delegate.embed(request), chatTimeLimiter);
    }

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        // Retry wraps only the connection call, NOT iterator consumption
        return executeWithResilience(() -> delegate.streamChat(request), streamingTimeLimiter);
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        return delegate.listModels();
    }

    // -------------------------------------------------------------------------
    // Batch API: delegated plainly, with no retry, no circuit breaker and no time limiter.
    //
    // The overrides are needed because this wrapper wraps every provider bean but the mock, and
    // the dispatcher picks a batch provider on capabilities().supportsBatch(), which this class
    // delegates. Without them the gate would say yes and the call would reach LlmProvider's
    // default, which throws.
    //
    // Plainly, because the chat policies are wrong here: uploadFile and createBatch are not
    // idempotent, so a retry submits the work twice and the provider bills it twice; and a batch is
    // asynchronous by construction, polled for minutes or hours, so a seconds-long time limiter
    // would refuse calls that are behaving as intended.
    // -------------------------------------------------------------------------

    @Override
    public String uploadFile(byte[] content, String filename, String purpose) {
        return delegate.uploadFile(content, filename, purpose);
    }

    @Override
    public String createBatch(String requestJson) {
        return delegate.createBatch(requestJson);
    }

    @Override
    public String getBatch(String batchId) {
        return delegate.getBatch(batchId);
    }

    @Override
    public String cancelBatch(String batchId) {
        return delegate.cancelBatch(batchId);
    }

    @Override
    public byte[] getFileContent(String fileId) {
        return delegate.getFileContent(fileId);
    }

    public LlmProvider getDelegate() {
        return delegate;
    }

    private <T> T executeWithResilience(Callable<T> callable, TimeLimiter timeLimiter) {
        try {
            Callable<T> withCircuitBreaker = CircuitBreaker.decorateCallable(circuitBreaker, callable);
            // Checked before every attempt, outside the breaker: a call whose caller timed out must not
            // start another upstream request, and its cancellation is not a provider failure.
            Callable<T> attempt = () -> {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Provider " + delegate.name() + " call was cancelled");
                }
                return withCircuitBreaker.call();
            };
            Callable<T> withRetry = Retry.decorateCallable(retry, attempt);

            // The upstream call runs on CALL_EXECUTOR, a different thread from the request thread.
            // RequestContextHolder is a ThreadLocal, so without an explicit copy the call would see
            // no request attributes, CredentialInterceptor.resolveWorkspaceId() would return null,
            // and workspace-scoped credential resolution would silently fall through to the
            // installation-wide credential. The attributes are captured here and restored inside
            // the task, with a reset in finally so they do not leak into the thread's next task.
            final RequestAttributes capturedAttrs = RequestContextHolder.getRequestAttributes();

            // TimeLimiter wraps a Future-based call; see CALL_EXECUTOR for why it is submitted
            var future = CALL_EXECUTOR.submit(() -> {
                final boolean attrsInjected = capturedAttrs != null;
                if (attrsInjected) {
                    RequestContextHolder.setRequestAttributes(capturedAttrs);
                }
                try {
                    return withRetry.call();
                } finally {
                    if (attrsInjected) {
                        RequestContextHolder.resetRequestAttributes();
                    }
                }
            });

            return timeLimiter.executeFutureSupplier(() -> future);
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker OPEN for provider [{}]: too many recent failures, "
                    + "requests are blocked until the provider recovers. {}", delegate.name(), e.getMessage());
            throw new GatewayException("PROVIDER_CIRCUIT_OPEN",
                    "Provider " + delegate.name() + " is temporarily unavailable — "
                    + "too many recent requests failed, so new requests are paused. "
                    + "This usually resolves automatically after a short cooldown. "
                    + "If it persists, check your provider API key and account status.");
        } catch (GatewayException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof GatewayException ge) {
                throw ge;
            }
            throw new GatewayException("PROVIDER_ERROR",
                    "Provider " + delegate.name() + " call failed: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "Provider " + delegate.name() + " call timed out", e);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GatewayException("PROVIDER_ERROR",
                    "Provider " + delegate.name() + " call failed: " + e.getMessage(), e);
        }
    }
}