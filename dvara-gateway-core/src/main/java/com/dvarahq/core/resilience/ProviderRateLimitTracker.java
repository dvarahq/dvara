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
package com.dvarahq.core.resilience;

import java.time.Duration;
import java.util.Map;

/**
 * Tracks each upstream <b>credential's</b> published rate-limit budget (from provider response
 * headers) and decides, before a call is forwarded, whether it should be shed because that credential
 * is near quota exhaustion. This sits <em>in front of</em> the resilience4j circuit breaker: it
 * lets the gateway shed / fail over proactively instead of hammering the provider until the breaker
 * opens with cross-workspace collateral damage.
 *
 * <p><b>Granularity is per credential</b>, not per workspace and not per provider-name — an upstream
 * limit is enforced against the API key that authenticates the call, so a shared platform key is one
 * window across workspaces while BYOK keys are isolated windows. The caller supplies a
 * {@code credentialIdentity} that is a stable, non-secret handle for the resolved key (a salted hash
 * of the secret).</p>
 *
 * <p><b>Spring-free by design</b> — {@code dvara-gateway-core} carries no Spring types, so
 * {@link #observe} takes response headers as a plain {@code Map<String,String>} (the interceptor
 * flattens {@code HttpHeaders} before calling). The provider wiring takes this seam optionally
 * and installs no interceptor when it is absent.</p>
 *
 * <h2>Not implemented in this build</h2>
 *
 * <p>Nothing in this repository implements this interface; another module or the application may
 * register an implementation, and it is then used. With no implementation, upstream quota headers
 * are not read and no request is shed ahead of a provider's own 429. The provider's rejection is
 * still handled; what is missing is anticipating it.
 */
public interface ProviderRateLimitTracker {

    /**
     * Decides whether a request to {@code provider} using {@code credentialIdentity} may be forwarded,
     * given the workspace's {@code tier} (shed order: bulk → standard → premium) and this request's
     * {@code estimatedTokens}. <b>Fail-open:</b> when there is no observed data for the credential yet
     * (cold start), the decision must be {@code allow=true} — a governance shed must never block
     * traffic it has no evidence to block.
     */
    Decision check(String provider, String credentialIdentity, String tier, int estimatedTokens);

    /**
     * Records the rate-limit budget a provider reported on a response. Called on <b>every</b> response
     * (200 <em>and</em> 429): success responses carry {@code remaining-*} headers (the proactive
     * signal), while a 429 sets a hard cooldown from {@code Retry-After} — the only signal some
     * providers (e.g. Bedrock, which publishes no remaining-quota headers) expose. Header names are
     * provider-specific and parsed inside the implementation; {@code statusCode} is the upstream HTTP
     * status.
     */
    void observe(String provider, String credentialIdentity, int statusCode, Map<String, String> responseHeaders);

    /**
     * A shed/allow decision. When {@code allow} is false, {@code retryAfter} is the provider's
     * advertised reset horizon (surfaced to the client as {@code Retry-After} when no fallback covers
     * the call).
     */
    record Decision(boolean allow, String reason, Duration retryAfter) {

        public static Decision allowed() {
            return new Decision(true, null, Duration.ZERO);
        }

        public static Decision shed(String reason, Duration retryAfter) {
            return new Decision(false, reason, retryAfter == null ? Duration.ZERO : retryAfter);
        }
    }
}
