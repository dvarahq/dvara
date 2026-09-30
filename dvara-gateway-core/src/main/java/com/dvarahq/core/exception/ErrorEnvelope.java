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
package com.dvarahq.server.web;

import com.dvarahq.core.util.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The error body a refusal is written in, chosen by the doorway it was made on.
 *
 * <p>A client reads errors in the shape its API defines. The Anthropic Messages doorway
 * ({@code /v1/messages} and everything under it) answers in Anthropic's envelope,
 * {@code {"type":"error","error":{"type":...,"message":...,"code":...},"request_id":...}}; every other
 * doorway in OpenAI's, {@code {"error":{"message":...,"type":...,"code":...,"trace_id":...}}}. The status and
 * the code are the same either way, so a refusal means the same thing on every doorway.</p>
 *
 * <p>A servlet filter that refuses a request writes its body here, so that a refusal made before any
 * controller runs (a missing key, a rate limit, an address that is not allowed) is in the right shape too.</p>
 */
public final class ErrorEnvelope {

    private ErrorEnvelope() {
    }

    /** Whether {@code uri} is on the Anthropic Messages doorway. */
    public static boolean anthropic(String uri) {
        return uri != null && (uri.equals("/v1/messages") || uri.startsWith("/v1/messages/"));
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
     * @param type  the OpenAI error type; the Anthropic envelope derives its own from the status
     * @param extra further members of the error object, such as a rate limit's details; may be null
     */
    public static Map<String, Object> body(String uri, int status, String message, String code, String type,
                                           String traceId, Map<String, Object> extra) {
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

    /** Writes a refusal: the status, a JSON body in the doorway's envelope, and the code for the access log. */
    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String message,
                             String code, String type, Map<String, Object> extra) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // what the access log, metrics and audit event record for this refusal
        request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, code);
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        response.getWriter().write(JsonMapper.instance().writeValueAsString(
                body(request.getRequestURI(), status, message, code, type, traceId, extra)));
    }
}
