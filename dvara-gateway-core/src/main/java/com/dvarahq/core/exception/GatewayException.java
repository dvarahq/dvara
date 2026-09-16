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
package com.dvarahq.core.exception;

import java.util.LinkedHashMap;
import java.util.Map;

public class GatewayException extends RuntimeException {

    private final String code;
    /**
     * Optional advertised retry horizon in seconds, surfaced to the client as a {@code Retry-After}
     * header (e.g. by {@code GlobalExceptionHandler} for {@code PROVIDER_RATE_LIMITED}). {@code null}
     * when the exception carries no retry hint.
     */
    private final Long retryAfterSeconds;
    /**
     * Fields the error body carries beside message, type, code and trace id, keyed by their wire
     * names. Empty for most errors. Lets a thrower add structured context — a cap, a usage figure, a
     * link — without the handler knowing what that error means.
     */
    private final Map<String, Object> details;
    /**
     * The HTTP status the upstream provider answered with, when this error reports one; {@code null}
     * otherwise. It lets the resilience layer tell a fault in the provider (5xx) from an error in the
     * caller's own request or key (4xx) without parsing the message.
     */
    private final Integer upstreamStatus;

    public GatewayException(String code, String message) {
        this(code, message, (Long) null);
    }

    public GatewayException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryAfterSeconds = null;
        this.details = Map.of();
        this.upstreamStatus = null;
    }

    public GatewayException(String code, String message, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.details = Map.of();
        this.upstreamStatus = null;
    }

    /** With structured fields for the error body; null values are dropped, order is kept. */
    public GatewayException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = null;
        Map<String, Object> kept = new LinkedHashMap<>();
        if (details != null) {
            details.forEach((k, v) -> { if (k != null && v != null) kept.put(k, v); });
        }
        this.details = java.util.Collections.unmodifiableMap(kept);
        this.upstreamStatus = null;
    }

    private GatewayException(String code, String message, int upstreamStatus) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = null;
        this.details = Map.of();
        this.upstreamStatus = upstreamStatus;
    }

    /** A {@code PROVIDER_ERROR} for an error status the upstream answered with, carrying that status. */
    public static GatewayException upstream(int status, String message) {
        return new GatewayException("PROVIDER_ERROR", message, status);
    }

    public String getCode() {
        return code;
    }

    /** The upstream HTTP status this error reports, or {@code null} if it reports none. */
    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    /** Advertised {@code Retry-After} in seconds, or {@code null} if none. */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Structured fields for the error body, keyed by wire name; empty when there are none. */
    public Map<String, Object> getDetails() {
        return details;
    }

    /**
     * Returns a human-readable hint for common HTTP status codes from upstream providers.
     */
    public static String describeHttpStatus(int statusCode) {
        return switch (statusCode) {
            case 401 -> " (invalid API key - check your provider credentials)";
            case 403 -> " (access denied - your API key may lack required permissions)";
            case 404 -> " (model or endpoint not found - check the model name)";
            case 429 -> " (rate limited by provider - too many requests)";
            case 500 -> " (provider internal server error)";
            case 502 -> " (provider returned bad gateway)";
            case 503 -> " (provider is temporarily unavailable)";
            default -> "";
        };
    }
}