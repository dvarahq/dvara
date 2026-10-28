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

/**
 * The error body a refusal is written in, chosen by the doorway it was made on.
 *
 * <p>A client reads errors in the shape its API defines. The Anthropic Messages doorway
 * ({@code /v1/messages} and everything under it) answers in Anthropic's envelope,
 * {@code {"type":"error","error":{"type":...,"message":...,"code":...},"request_id":...}}. The Gemini
 * {@code generateContent} doorway ({@code /v1/gemini/} and everything under it) answers in Google's,
 * {@code {"error":{"code":<status>,"message":...,"status":"INVALID_ARGUMENT","details":[...]}}}, with the
 * gateway's code and trace id in an {@code ErrorInfo} detail. Every other doorway answers in OpenAI's,
 * {@code {"error":{"message":...,"type":...,"code":...,"trace_id":...}}}. The status and the code are the
 * same either way, so a refusal means the same thing on every doorway.</p>
 *
 * <p>A servlet filter that refuses a request builds its body here, so that a refusal made before any
 * controller runs (a missing key, a rate limit, an address that is not allowed) is in the right shape too.</p>
 */
public final class ErrorEnvelope {

    private ErrorEnvelope() {
    }

    /** Whether {@code uri} is on the Anthropic Messages doorway. */
    public static boolean anthropic(String uri) {
        return uri != null && (uri.equals("/v1/messages") || uri.startsWith("/v1/messages/"));
    }

    /** Whether {@code uri} is on the Gemini {@code generateContent} doorway. */
    public static boolean gemini(String uri) {
        return uri != null && (uri.equals("/v1/gemini") || uri.startsWith("/v1/gemini/"));
    }

    /** Google's canonical error status for an HTTP status. */
    public static String googleStatus(int status) {
        return switch (status) {
            case 400, 413, 422 -> "INVALID_ARGUMENT";
            case 401 -> "UNAUTHENTICATED";
            case 402, 403 -> "PERMISSION_DENIED";
            case 404 -> "NOT_FOUND";
            case 409 -> "ABORTED";
            case 429 -> "RESOURCE_EXHAUSTED";
            case 499 -> "CANCELLED";
            case 501 -> "UNIMPLEMENTED";
            case 502, 503, 529 -> "UNAVAILABLE";
            case 504 -> "DEADLINE_EXCEEDED";
            default -> "INTERNAL";
        };
    }

    /** Anthropic's error type for an HTTP status. */
    public static String anthropicType(int status) {
        return switch (status) {
            case 400, 422 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 402, 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 413 -> "request_too_large";
            case 429 -> "rate_limit_error";
            case 503, 529 -> "overloaded_error";
            default -> "api_error";
        };
    }

    /**
     * The body of a refusal on {@code uri}.
     *
     * @param code  the gateway's error code; written in lower case
     * @param type  the OpenAI error type; the Anthropic envelope derives its own from the status
     * @param extra further members of the error object, such as a rate limit's details; may be null
     */
    public static Map<String, Object> body(String uri, int status, String message, String code, String type,
                                           String traceId, Map<String, Object> extra) {
        // Every code goes out in lower case, on every doorway, whatever case the caller raised it in.
        if (code != null) {
            code = code.toLowerCase(java.util.Locale.ROOT);
        }
        Map<String, Object> error = new LinkedHashMap<>();
        if (anthropic(uri)) {
            error.put("type", anthropicType(status));
            error.put("message", message == null ? "" : message);
            if (code != null) {
                error.put("code", code);
            }
            if (extra != null) {
                extra.forEach(error::putIfAbsent);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "error");
            body.put("error", error);
            if (traceId != null && !traceId.isEmpty()) {
                body.put("request_id", traceId);
            }
            return body;
        }
        if (gemini(uri)) {
            error.put("code", status);
            error.put("message", message == null ? "" : message);
            error.put("status", googleStatus(status));
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (traceId != null && !traceId.isEmpty()) {
                metadata.put("trace_id", traceId);
            }
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("@type", "type.googleapis.com/google.rpc.ErrorInfo");
            info.put("reason", code == null ? googleStatus(status) : code);
            info.put("domain", "dvara");
            info.put("metadata", metadata);
            error.put("details", java.util.List.of(info));
            if (extra != null) {
                extra.forEach(error::putIfAbsent);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", error);
            return body;
        }
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        error.put("trace_id", traceId != null ? traceId : "");
        if (extra != null) {
            extra.forEach(error::putIfAbsent);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
