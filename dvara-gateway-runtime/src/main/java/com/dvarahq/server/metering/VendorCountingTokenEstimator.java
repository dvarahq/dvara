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
package com.dvarahq.server.metering;

import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.core.util.TtlLruCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Counts a request's tokens by asking the provider that would serve it, where that provider can count (Anthropic's
 * {@code count_tokens}, Gemini's {@code countTokens}), and falls back to the local count otherwise. Opt-in:
 * {@code dvara.llm-gateway.token-count.vendor.enabled}, {@code true} for every request or {@code selected} for the
 * requests a {@code VendorTokenCountSelector} picks.
 *
 * <p>The local count uses one vocabulary per family and a stand-in for families whose own tokenizer is not available
 * here; a vendor's count is that vendor's own. The price is a network call on the request path, so:
 *
 * <ul>
 *   <li><b>Cached</b>, by the request's model, messages and tools, for {@code ttl} (60 s by default) and up to
 *       {@code cache-size} entries, so the rate limiter, the context check and the metering of one request ask once.</li>
 *   <li><b>Bounded</b>: a vendor that has not answered in {@code timeout} (300 ms by default) is not waited for.</li>
 *   <li><b>Fail-open</b>: no answer, an error, a provider with no counting endpoint, or a request with no workspace all
 *       give the local count. A count is never worth failing a request over.</li>
 *   <li><b>Only with a workspace</b>: counting sends the request's text to the provider, under the credential that
 *       workspace's call would use. A request whose workspace is not known yet is counted locally.</li>
 * </ul>
 *
 * <p>{@link #estimateTokens(String)} and {@link #upperBound(ChatRequest)} are the local ones: a text with no request
 * has no provider to ask, and the bound is the cheap check that decides whether counting is needed at all.
 */
public class VendorCountingTokenEstimator implements TokenEstimator {

    private static final Logger log = LoggerFactory.getLogger(VendorCountingTokenEstimator.class);

    private final TokenEstimator local;
    private final Function<ChatRequest, OptionalInt> vendor;
    private final Predicate<ChatRequest> selected;
    private final Duration timeout;
    private final TtlLruCache<Integer> cache;
    private final int ttlSeconds;

    /**
     * @param local      the count used whenever the vendor's is not available
     * @param vendor     the provider's count for a request, or empty when it cannot count it
     * @param timeout    how long to wait for the vendor
     * @param ttl        how long a vendor's count is kept
     * @param cacheSize  how many counts are kept
     */
    public VendorCountingTokenEstimator(TokenEstimator local, Function<ChatRequest, OptionalInt> vendor,
                                        Duration timeout, Duration ttl, int cacheSize) {
        this(local, vendor, request -> true, timeout, ttl, cacheSize);
    }

    /**
     * @param selected which requests the vendor counts; the rest are counted locally
     */
    public VendorCountingTokenEstimator(TokenEstimator local, Function<ChatRequest, OptionalInt> vendor,
                                        Predicate<ChatRequest> selected, Duration timeout, Duration ttl,
                                        int cacheSize) {
        this.local = local;
        this.vendor = vendor;
        this.selected = selected;
        this.timeout = timeout;
        this.ttlSeconds = (int) Math.max(1, ttl.toSeconds());
        this.cache = new TtlLruCache<>(Math.max(1, cacheSize), ttlSeconds);
    }

    @Override
    public int estimateTokens(ChatRequest request) {
        if (request == null || workspaceOf(request) == null || !isSelected(request)) {
            return local.estimateTokens(request);
        }
        String key = key(request);
        Integer cached = key == null ? null : cache.get(key);
        if (cached != null) {
            return cached;
        }
        OptionalInt counted = askVendor(request);
        if (counted.isPresent() && counted.getAsInt() > 0) {
            if (key != null) {
                cache.put(key, counted.getAsInt(), ttlSeconds);
            }
            return counted.getAsInt();
        }
        return local.estimateTokens(request);
    }

    @Override
    public int estimateTokens(String text) {
        return local.estimateTokens(text);
    }

    @Override
    public int upperBound(ChatRequest request) {
        return local.upperBound(request);
    }

    /** The local count's parts: a provider's count is a total only. */
    @Override
    public java.util.Optional<com.dvarahq.core.guardrail.TokenBreakdown> breakdown(ChatRequest request) {
        return local.breakdown(request);
    }

    /** A selector that fails picks nothing: the local count is used. */
    private boolean isSelected(ChatRequest request) {
        try {
            return selected.test(request);
        } catch (RuntimeException e) {
            log.debug("Vendor count selector failed for {}, counting locally: {}", request.getModel(), e.getMessage());
            return false;
        }
    }

    private OptionalInt askVendor(ChatRequest request) {
        try {
            return CompletableFuture.supplyAsync(() -> vendor.apply(request), r -> Thread.ofVirtual().start(r))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Vendor token count for {} unavailable, counting locally: {}", request.getModel(),
                    e.getMessage());
            return OptionalInt.empty();
        }
    }

    static String workspaceOf(ChatRequest request) {
        Object ws = request.getMetadata() == null ? null : request.getMetadata().get("workspace_id");
        return ws == null || ws.toString().isBlank() ? null : ws.toString();
    }

    /** A digest of what a provider would count, with the workspace, so two workspaces never share a count. */
    private static String key(ChatRequest request) {
        try {
            Map<String, Object> parts = new LinkedHashMap<>();
            parts.put("w", workspaceOf(request));
            parts.put("m", request.getModel());
            parts.put("msg", request.getMessages());
            parts.put("t", request.getTools());
            byte[] json = JsonMapper.instance().writeValueAsString(parts).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            return null;
        }
    }
}
