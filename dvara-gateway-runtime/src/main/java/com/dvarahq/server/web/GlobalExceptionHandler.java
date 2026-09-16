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

import com.dvarahq.core.plane.GatewayPlane;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.server.v1.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.stream.Collectors;

// Unscoped, and it must stay unscoped: its 404 and MaxUploadSize handlers fire for requests
// that reached no controller, and a basePackages-scoped advice is never consulted for those.
//
// The order is explicit and distinct. Spring's ExceptionHandlerExceptionResolver takes the first
// advice that can resolve any method for the thrown type, so a catch-all in an earlier advice
// beats a specific handler in a later one, and two advices at the same order are tied and broken
// by bean discovery order. This one comes first among the catch-all advices, since it owns the
// Spring MVC exceptions raised before a controller is selected (405/415/406/400 and 404); an
// advice another module registers for its own plane should sit just after it. Advices at
// HIGHEST_PRECEDENCE that handle a plane's GatewayException are unaffected.
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 30)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        String param = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .collect(Collectors.joining(", "));
        return ErrorResponse.builder()
                .error(ErrorResponse.ErrorDetail.builder()
                        .message(message)
                        .type("invalid_request_error")
                        .code("validation_error")
                        .param(param)
                        .traceId(traceId)
                        .build())
                .build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnreadable(HttpMessageNotReadableException ex,
                                          HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        return ErrorResponse.of("Invalid or missing request body: " + ex.getMessage(),
                "invalid_request_error", "invalid_json", traceId);
    }

    /**
     * Oversized multipart upload (e.g. Batch API {@code POST /v1/files}). Handled here — not on
     * the controller — because Spring resolves the multipart request and throws before a handler is
     * selected, so a controller-local {@code @ExceptionHandler} is never consulted; only this global
     * {@code @RestControllerAdvice} catches it, and it reuses the standard error envelope + trace id.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public ErrorResponse handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                              HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        return ErrorResponse.of("Uploaded file exceeds the configured size limit",
                "input_size_error", "FILE_TOO_LARGE", traceId);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGatewayException(GatewayException ex,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, ex.getCode());
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        String code = ex.getCode();
        HttpStatus status;
        String type;

        if ("WORKSPACE_NOT_FOUND".equals(code) || "API_KEY_NOT_FOUND".equals(code)
                || "ROUTE_NOT_FOUND".equals(code) || "ROUTE_VERSION_NOT_FOUND".equals(code)
                || "POLICY_NOT_FOUND".equals(code) || "POLICY_VERSION_NOT_FOUND".equals(code)
                || "REPORT_NOT_FOUND".equals(code)
                || "WEBHOOK_NOT_FOUND".equals(code)
                || "MCP_SERVER_NOT_FOUND".equals(code)
                || "USER_NOT_FOUND".equals(code)
                || "PRICING_NOT_FOUND".equals(code)
                || "BUDGET_NOT_FOUND".equals(code)
                || "CHARGEBACK_NOT_FOUND".equals(code)
                || "SCHEMA_NOT_FOUND".equals(code)
                || "SESSION_NOT_FOUND".equals(code)
                || "PROMPT_EXPERIMENT_NOT_FOUND".equals(code)
                || "PROMPT_TEMPLATE_NOT_FOUND".equals(code)
                || "PROMPT_VERSION_NOT_FOUND".equals(code)
                || "BATCH_NOT_FOUND".equals(code)
                || "FILE_NOT_FOUND".equals(code)) {
            status = HttpStatus.NOT_FOUND;
            type = "not_found_error";
        } else if ("NO_PROVIDER".equals(code) || "NO_CAPABLE_PROVIDER".equals(code)) {
            status = HttpStatus.BAD_REQUEST;
            type = "invalid_request_error";
        } else if ("POLICY_DENIED".equals(code)) {
            status = HttpStatus.FORBIDDEN;
            type = "policy_violation";
        } else if ("DATA_RESIDENCY_VIOLATION".equals(code)) {
            status = HttpStatus.FORBIDDEN;
            type = "data_residency_error";
        } else if ("IP_ACCESS_DENIED".equals(code)) {
            status = HttpStatus.FORBIDDEN;
            type = "ip_access_error";
        } else if ("GUARDRAIL_BLOCKED".equals(code) || "HALLUCINATION_DETECTED".equals(code)) {
            status = HttpStatus.FORBIDDEN;
            type = "guardrail_violation";
        } else if ("GUARDRAIL_PLUGIN_ERROR".equals(code)) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            type = "guardrail_plugin_error";
        } else if ("SCHEMA_VALIDATION_FAILED".equals(code)) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            type = "schema_validation_error";
        } else if ("CONTEXT_WINDOW_EXCEEDED".equals(code)) {
            status = HttpStatus.BAD_REQUEST;
            type = "context_window_error";
        } else if ("INPUT_TOO_LARGE".equals(code)) {
            status = HttpStatus.PAYLOAD_TOO_LARGE;
            type = "input_size_error";
        } else if ("PII_DETECTED".equals(code)) {
            status = HttpStatus.BAD_REQUEST;
            type = "pii_violation";
        } else if ("PII_TOKENIZE_UNAVAILABLE".equals(code)) {
            // The workspace asked for TOKENIZE and nothing here can honour it. No provider was
            // involved and the refusal is stable, so 403 rather than a retryable 5xx.
            status = HttpStatus.FORBIDDEN;
            type = "pii_violation";
        } else if ("GROUNDING_UNAVAILABLE".equals(code)) {
            // Grounding is switched on for the workspace and no detector is registered. Same shape
            // as PII_TOKENIZE_UNAVAILABLE: no provider was involved and the refusal is stable, so
            // it must not land in the retryable 5xx class where an SDK will hammer it.
            status = HttpStatus.FORBIDDEN;
            type = "guardrail_violation";
        } else if ("PII_REDACT_UNAVAILABLE".equals(code)) {
            // The gateway refused on its own policy because it could not fetch the workspace's key;
            // no provider was contacted, so not a 502, which would send an operator to the wrong
            // place and invite an SDK to retry a stable refusal.
            //
            // Only TOKENIZE can reach this. REDACT stores nothing and needs no key, so it cannot be
            // unavailable; the code keeps its name because clients branch on error.code, and the
            // response carries attempted_action so a client need not parse the message.
            //
            // Grouped with PII_DETECTED under pii_violation because it is the same family from the
            // caller's side: the request carried PII and was refused.
            status = HttpStatus.FORBIDDEN;
            type = "pii_violation";
        } else if ("MCP_SERVER_DUPLICATE".equals(code) || "USER_DUPLICATE".equals(code)) {
            status = HttpStatus.CONFLICT;
            type = "duplicate_error";
        } else if ("INVALID_REQUEST".equals(code) || "UNSUPPORTED_RESPONSE_FORMAT".equals(code)
                || "UNSUPPORTED_CAPABILITY".equals(code)
                || "INVALID_POLICY_STATUS".equals(code) || "COMPLIANCE_NOT_AVAILABLE".equals(code)
                || "INVALID_REPORT_TYPE".equals(code) || "MCP_NOT_AVAILABLE".equals(code)
                || "CHARGEBACK_NOT_AVAILABLE".equals(code) || "BUDGET_ESTIMATE_FAILED".equals(code)
                || "PROMPT_EXPERIMENT_NOT_RUNNING".equals(code)
                || "PROMPT_TEMPLATE_NOT_ACTIVE".equals(code)
                || "PROMPT_VARIABLE_MISSING".equals(code)
                || "INVALID_TEMPLATE_STATUS".equals(code)
                || "CREDENTIAL_NOT_ROTATABLE".equals(code)
                || "CREDENTIAL_STORAGE_MODE_MISMATCH".equals(code)
                || "CREDENTIAL_REFERENCE_MISSING".equals(code)
                || "CREDENTIAL_API_KEY_MISSING".equals(code)
                || "INVALID_STORAGE_MODE".equals(code)
                || "ENCRYPTION_NOT_CONFIGURED".equals(code)
                || "INVALID_OUTPUT_SCHEMA_SCOPE".equals(code)
                || "INVALID_POLICY_DSL".equals(code)) {
            status = HttpStatus.BAD_REQUEST;
            type = "invalid_request_error";
        } else if ("CREDENTIAL_NOT_FOUND".equals(code)) {
            status = HttpStatus.NOT_FOUND;
            type = "not_found_error";
        } else if ("BUDGET_CAP_HARD".equals(code)) {
            status = HttpStatus.PAYMENT_REQUIRED;
            type = "budget_exceeded";
        } else if ("PER_CALL_COST_EXCEEDED".equals(code)) {
            // a single request's estimated cost exceeded the per-call ceiling. Same 402 family as
            // BUDGET_CAP_HARD (a cost limit), but a per-call gate rather than accumulated spend.
            status = HttpStatus.PAYMENT_REQUIRED;
            type = "budget_exceeded";
        } else if ("LICENSE_TOKEN_CAP_EXCEEDED".equals(code)) {
            // A monthly usage cap was reached. Same status as BUDGET_CAP_HARD — both refuse a call
            // on a quota rather than a fault. Whatever raised it puts its own structured fields on
            // the details map; this handler adds none and needs to know nothing about them.
            status = HttpStatus.PAYMENT_REQUIRED;
            type = "token_cap_exceeded";
        } else if ("WORKSPACE_CREDENTIAL_REQUIRED".equals(code)) {
            // Strict-BYOK enforcement rejected the call because the workspace has no
            // own provider credential. 403 (not 402): the workspace is forbidden
            // from borrowing the platform-default; payment isn't the issue.
            status = HttpStatus.FORBIDDEN;
            type = "workspace_credential_required";
        } else if ("WORKSPACE_CREDENTIAL_UNAVAILABLE".equals(code)) {
            // The workspace has its own provider credential and it cannot be used right now: it will
            // not decrypt, its vault reference does not resolve, or the store cannot be read. 503: the
            // call was refused rather than made on another key, and succeeds once that is fixed.
            status = HttpStatus.SERVICE_UNAVAILABLE;
            type = "workspace_credential_unavailable";
        } else if ("WORKSPACE_CAP_EXCEEDED".equals(code)) {
            // A cap on how many workspaces may exist. 403: another cannot be created until one is
            // deleted or the cap is raised. Not 402 — the refusal is about a limit, not payment.
            status = HttpStatus.FORBIDDEN;
            type = "workspace_cap_exceeded";
        } else if ("WORKSPACE_SUSPENDED".equals(code)) {
            // The workspace has been administratively suspended. 403, not 402: the workspace is
            // forbidden from the data plane regardless of payment state.
            status = HttpStatus.FORBIDDEN;
            type = "workspace_suspended";
        } else if ("RATE_LIMIT_EXCEEDED".equals(code)) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            type = "rate_limit_error";
        } else if ("PRIORITY_THROTTLED".equals(code)) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            type = "priority_throttle_error";
        } else if ("PROVIDER_RATE_LIMITED".equals(code)) {
            // the upstream credential's quota is near exhaustion and no fallback provider covered
            // the call. Surface a 429 with the provider's advertised Retry-After (added on the builder).
            status = HttpStatus.TOO_MANY_REQUESTS;
            type = "provider_rate_limited";
        } else if ("PROVIDER_CIRCUIT_OPEN".equals(code) || "FAILOVER_CAPABILITY_MISMATCH".equals(code)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            type = "provider_unavailable";
        } else {
            status = HttpStatus.BAD_GATEWAY;
            type = "provider_error";
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if ("FAILOVER_CAPABILITY_MISMATCH".equals(code)) {
            builder.header("X-Gateway-Failover-Blocked", "capability_mismatch");
        }
        if (ex.getRetryAfterSeconds() != null) { // a provider rate-limit shed carries a Retry-After
            builder.header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        }

        // One body, with every structured field applied to it: an error carrying both a details
        // map and an attempted action must not lose either to an early return.
        var detail = ErrorResponse.ErrorDetail.builder()
                .message(ex.getMessage())
                .type(type)
                .code(code.toLowerCase(java.util.Locale.ROOT))   // a Turkish default locale made PII_DETECTED pıı_detected
                .traceId(traceId);

        // Fields the exception itself carries, under their own wire names. The handler needs no
        // knowledge of what a given code means — whatever raises one decides which fields ride
        // along, and they are written verbatim. An empty map writes nothing: details is @JsonIgnore
        // behind a @JsonAnyGetter, so every error carrying none is byte-for-byte unchanged.
        detail.details(ex.getDetails());

        // The code says REDACT for compatibility (see the branch above), so the response states the
        // action that actually failed rather than leaving a new client to parse the message for it.
        // Constant because only TOKENIZE can reach that code. NON_NULL keeps it off every other error.
        if ("PII_REDACT_UNAVAILABLE".equals(code)) {
            detail.attemptedAction("TOKENIZE");
        }

        return builder.body(ErrorResponse.builder().error(detail.build()).build());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(NoResourceFoundException ex, HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);

        // a path under /v1/ is this plane's to answer; anything else is not. The two shapes
        // are close here (both ErrorResponse), so the distinction is carried in the CODE:
        // resource_not_found means "the LLM plane has no such route", not_found means "no plane
        // claims this path at all". Merged, that is what stops /mcp/typo answering as an LLM error.
        if (GatewayPlane.resolve(failedPath(ex)) != GatewayPlane.LLM) {
            return ErrorResponse.of("Not found", "not_found_error", "not_found", traceId);
        }
        return ErrorResponse.of("Resource not found",
                "not_found_error", "resource_not_found", traceId);
    }

    /**
     * The Spring MVC exceptions raised before a controller is selected (405, 415, 406, 400). Each
     * is a specific 4xx and must not fall through to {@link #handleGeneric} as a 500: 5xx is
     * retryable, so a client would hammer a request that can never succeed, and every such client
     * mistake would count as a gateway failure in {@code gateway_requests_total{status}}.
     *
     * <p>Deliberately not {@code ResponseEntityExceptionHandler}, which would answer these with
     * Spring's {@code ProblemDetail} body while every other error on this plane is an
     * {@link ErrorResponse}. The cost is that a new Spring exception is not covered automatically.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);

        // RFC 9110 §15.5.6 makes Allow REQUIRED on a 405, and it is the only part of this response
        // that tells the caller what to do instead.
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(ErrorResponse.of(
                "Method " + ex.getMethod() + " is not supported for this endpoint",
                "invalid_request_error", "method_not_allowed", traceId));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ErrorResponse handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                    HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        return ErrorResponse.of(
                "Content-Type " + ex.getContentType() + " is not supported; this endpoint expects "
                        + "application/json",
                "invalid_request_error", "unsupported_media_type", traceId);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    @ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
    public ErrorResponse handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                             HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        return ErrorResponse.of("No representation matches the Accept header; this endpoint "
                        + "produces application/json",
                "invalid_request_error", "not_acceptable", traceId);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleMissingParameter(MissingServletRequestParameterException ex,
                                                HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        return ErrorResponse.of("Required parameter '" + ex.getParameterName() + "' is missing",
                "invalid_request_error", "missing_parameter", traceId);
    }

    /**
     * The catch-all. Logs the exception with the {@code X-Trace-ID} the body hands the caller, so
     * an operator given that id can find the cause; the structured {@code traceId} log field is
     * the OTel id, a different value. The body stays generic on purpose: it faces the caller and
     * must not leak internals.
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex, HttpServletResponse response) {
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        log.error("Unhandled exception [trace_id={}]", traceId, ex);
        return ErrorResponse.of("An unexpected error occurred",
                "gateway_error", "internal_error", traceId);
    }
    /**
     * The path that failed to resolve, taken from the exception rather than an injected
     * {@code HttpServletRequest}, which stops a {@code @ExceptionHandler} being invoked. It is also
     * the better source: the path Spring could not route, rather than whatever the current request
     * happens to be. {@code getResourcePath()} has no leading slash, so it is normalised here;
     * otherwise every prefix comparison would fail.
     */
    private static String failedPath(Exception ex) {
        if (ex instanceof NoResourceFoundException nrf) {
            String p = nrf.getResourcePath();
            return p == null ? "" : (p.startsWith("/") ? p : "/" + p);
        }
        return "";
    }

}