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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.server.v1.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // -------------------------------------------------------------------------
    // handleGatewayException — WORKSPACE_NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_tenantNotFound_returns404() {
        HttpServletResponse response = mockResponse("trace-workspace");
        GatewayException ex = new GatewayException("WORKSPACE_NOT_FOUND", "Workspace not found: t-999");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().getError().getType()).isEqualTo("not_found_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("workspace_not_found");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Workspace not found: t-999");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-workspace");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — NO_PROVIDER
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_noProvider_returns400() {
        HttpServletResponse response = mockResponse("trace-1");
        GatewayException ex = new GatewayException("NO_PROVIDER", "no provider for model");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("no_provider");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("no provider for model");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-1");
    }

    @Test
    void handleGatewayException_unsupportedCapability_returns400() {
        // UNSUPPORTED_CAPABILITY is thrown by Cohere, Groq, Ollama when a
        // request asks for a capability they don't support (vision, tool
        // calls). It's a request-side mismatch, not a provider outage —
        // 400 invalid_request_error matches UNSUPPORTED_RESPONSE_FORMAT,
        // which has the same shape.
        HttpServletResponse response = mockResponse("trace-vision");
        GatewayException ex = new GatewayException("UNSUPPORTED_CAPABILITY",
                "Cohere vision is not yet implemented in DVARA.");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("unsupported_capability");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-vision");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROVIDER_ERROR
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_providerError_returns502() {
        HttpServletResponse response = mockResponse("trace-2");
        GatewayException ex = new GatewayException("PROVIDER_ERROR", "upstream failed");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(result.getBody().getError().getType()).isEqualTo("provider_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("provider_error");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("upstream failed");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-2");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROVIDER_RATE_LIMITED: 429 + Retry-After
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_providerRateLimited_returns429WithRetryAfter() {
        HttpServletResponse response = mockResponse("trace-rl");
        GatewayException ex = new GatewayException("PROVIDER_RATE_LIMITED",
                "Upstream openai rate limit near exhaustion", 20L);

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.getBody().getError().getType()).isEqualTo("provider_rate_limited");
        assertThat(result.getBody().getError().getCode()).isEqualTo("provider_rate_limited");
        assertThat(result.getHeaders().getFirst("Retry-After")).isEqualTo("20");
    }

    @Test
    void handleGatewayException_withoutRetryAfter_omitsHeader() {
        HttpServletResponse response = mockResponse("trace-nr");
        GatewayException ex = new GatewayException("PROVIDER_ERROR", "upstream failed");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getHeaders().getFirst("Retry-After")).isNull();
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROVIDER_CIRCUIT_OPEN
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_circuitOpen_returns503() {
        HttpServletResponse response = mockResponse("trace-3");
        GatewayException ex = new GatewayException("PROVIDER_CIRCUIT_OPEN",
                "Provider openai is temporarily unavailable");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody().getError().getType()).isEqualTo("provider_unavailable");
        assertThat(result.getBody().getError().getCode()).isEqualTo("provider_circuit_open");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Provider openai is temporarily unavailable");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-3");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — RATE_LIMIT_EXCEEDED
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_rateLimitExceeded_returns429() {
        HttpServletResponse response = mockResponse("trace-rl");
        GatewayException ex = new GatewayException("RATE_LIMIT_EXCEEDED", "Too many requests");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(result.getBody().getError().getType()).isEqualTo("rate_limit_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("rate_limit_exceeded");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Too many requests");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-rl");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — degraded redaction is a governance refusal, not an upstream failure
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_piiRedactUnavailable_is403NotAnUpstreamFailure() {
        // Two things are asserted: the status must be a 4xx, because this refusal is stable until an
        // operator acts and an SDK retrying a 5xx would hammer an already-unreachable control plane;
        // and the type must not say provider_error, because no provider was contacted.
        HttpServletResponse response = mockResponse("trace-pii");
        GatewayException ex = new GatewayException("PII_REDACT_UNAVAILABLE",
                "Request blocked: PII detected and tokenization is unavailable on this pod (SSN)");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getStatusCode().is5xxServerError()).isFalse();
        assertThat(result.getBody().getError().getType()).isEqualTo("pii_violation");
        assertThat(result.getBody().getError().getType()).isNotEqualTo("provider_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("pii_redact_unavailable");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — unknown codes default to 502
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_unknownCode_returns502() {
        HttpServletResponse response = mockResponse("trace-4");
        GatewayException ex = new GatewayException("SOMETHING_ELSE", "unexpected code");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(result.getBody().getError().getType()).isEqualTo("provider_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("something_else");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — trace ID
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_includesTraceId() {
        HttpServletResponse response = mockResponse("abc-123-def");
        GatewayException ex = new GatewayException("PROVIDER_ERROR", "error");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getBody().getError().getTraceId()).isEqualTo("abc-123-def");
    }

    @Test
    void handleGatewayException_nullTraceId() {
        HttpServletResponse response = mockResponse(null);
        GatewayException ex = new GatewayException("PROVIDER_ERROR", "error");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getBody().getError().getTraceId()).isNull();
    }

    // -------------------------------------------------------------------------
    // handleGeneric
    // -------------------------------------------------------------------------

    @Test
    void handleGeneric_returns500WithGenericMessage() {
        HttpServletResponse response = mockResponse("trace-5");
        Exception ex = new RuntimeException("unexpected");

        ErrorResponse result = handler.handleGeneric(ex, response);

        assertThat(result.getError().getMessage()).isEqualTo("An unexpected error occurred");
        assertThat(result.getError().getType()).isEqualTo("gateway_error");
        assertThat(result.getError().getCode()).isEqualTo("internal_error");
        assertThat(result.getError().getTraceId()).isEqualTo("trace-5");
    }

    @Test
    void handleGeneric_doesNotLeakExceptionDetails() {
        HttpServletResponse response = mockResponse("trace-6");
        Exception ex = new RuntimeException("sensitive stack trace info");

        ErrorResponse result = handler.handleGeneric(ex, response);

        assertThat(result.getError().getMessage()).doesNotContain("sensitive");
        assertThat(result.getError().getMessage()).isEqualTo("An unexpected error occurred");
    }

    /**
     * The server keeps what it declines to send. The body hands the caller a trace id and invites
     * them to quote it, so the ERROR line must carry that same id and the exception itself; a log
     * line nobody can correlate would satisfy a weaker "something was logged" check.
     */
    @Test
    void handleGeneric_logsTheCauseUnderTheSameTraceIdItReturns() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            HttpServletResponse response = mockResponse("trace-1891");
            Exception ex = new IllegalStateException("the cause an operator needs");

            ErrorResponse result = handler.handleGeneric(ex, response);

            assertThat(appender.list)
                    .as("a 500 that logs nothing cannot be diagnosed")
                    .isNotEmpty();
            ch.qos.logback.classic.spi.ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .as("the logged id must be the one the caller was told to quote")
                    .contains(result.getError().getTraceId());
            assertThat(event.getThrowableProxy())
                    .as("the exception itself, not just its message — a stack trace is the diagnosis")
                    .isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).contains("the cause an operator needs");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // -------------------------------------------------------------------------
    // handleUnreadable
    // -------------------------------------------------------------------------

    @Test
    void handleUnreadable_returns400WithMessage() {
        HttpServletResponse response = mockResponse("trace-7");
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error", (org.springframework.http.HttpInputMessage) null);

        ErrorResponse result = handler.handleUnreadable(ex, response);

        assertThat(result.getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getError().getCode()).isEqualTo("invalid_json");
        assertThat(result.getError().getMessage()).contains("Invalid or missing request body");
        assertThat(result.getError().getTraceId()).isEqualTo("trace-7");
    }

    // -------------------------------------------------------------------------
    // Code lowercasing
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_lowercasesCode() {
        HttpServletResponse response = mockResponse("trace-8");
        GatewayException ex = new GatewayException("PROVIDER_CIRCUIT_OPEN", "circuit open");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getBody().getError().getCode()).isEqualTo("provider_circuit_open");
    }

    @Test
    void handleGatewayException_lowercasesNoProviderCode() {
        HttpServletResponse response = mockResponse("trace-9");
        GatewayException ex = new GatewayException("NO_PROVIDER", "no provider");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getBody().getError().getCode()).isEqualTo("no_provider");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — NO_CAPABLE_PROVIDER
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_noCapableProvider_returns400() {
        HttpServletResponse response = mockResponse("trace-cap");
        GatewayException ex = new GatewayException("NO_CAPABLE_PROVIDER",
                "No provider supports response_format: json_schema");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("no_capable_provider");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — FAILOVER_CAPABILITY_MISMATCH
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_failoverCapabilityMismatch_returns503WithHeader() {
        HttpServletResponse response = mockResponse("trace-fo");
        GatewayException ex = new GatewayException("FAILOVER_CAPABILITY_MISMATCH",
                "Failover blocked: no fallback provider supports response_format: json_schema");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody().getError().getType()).isEqualTo("provider_unavailable");
        assertThat(result.getBody().getError().getCode()).isEqualTo("failover_capability_mismatch");
        assertThat(result.getHeaders().getFirst("X-Gateway-Failover-Blocked")).isEqualTo("capability_mismatch");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROMPT_EXPERIMENT_NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_promptExperimentNotFound_returns404() {
        HttpServletResponse response = mockResponse("trace-exp-nf");
        GatewayException ex = new GatewayException("PROMPT_EXPERIMENT_NOT_FOUND", "Prompt experiment not found: exp-1");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().getError().getType()).isEqualTo("not_found_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("prompt_experiment_not_found");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Prompt experiment not found: exp-1");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-exp-nf");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROMPT_EXPERIMENT_NOT_RUNNING
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_promptExperimentNotRunning_returns400() {
        HttpServletResponse response = mockResponse("trace-exp-nr");
        GatewayException ex = new GatewayException("PROMPT_EXPERIMENT_NOT_RUNNING", "Prompt experiment is not running: exp-1");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("prompt_experiment_not_running");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Prompt experiment is not running: exp-1");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-exp-nr");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROMPT_TEMPLATE_NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_promptTemplateNotFound_returns404() {
        HttpServletResponse response = mockResponse("trace-pt-nf");
        GatewayException ex = new GatewayException("PROMPT_TEMPLATE_NOT_FOUND", "Prompt template not found: pt-1");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().getError().getType()).isEqualTo("not_found_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("prompt_template_not_found");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Prompt template not found: pt-1");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-pt-nf");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROMPT_TEMPLATE_NOT_ACTIVE
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_promptTemplateNotActive_returns400() {
        HttpServletResponse response = mockResponse("trace-pt-na");
        GatewayException ex = new GatewayException("PROMPT_TEMPLATE_NOT_ACTIVE", "Prompt template is not active: pt-1");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("prompt_template_not_active");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Prompt template is not active: pt-1");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-pt-na");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — PROMPT_VARIABLE_MISSING
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_promptVariableMissing_returns400() {
        HttpServletResponse response = mockResponse("trace-pv-m");
        GatewayException ex = new GatewayException("PROMPT_VARIABLE_MISSING", "Missing variable: name");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("prompt_variable_missing");
        assertThat(result.getBody().getError().getMessage()).isEqualTo("Missing variable: name");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-pv-m");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — HALLUCINATION_DETECTED
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_hallucinationDetected_returns403AsGuardrailViolation() {
        HttpServletResponse response = mockResponse("trace-hall");
        GatewayException ex = new GatewayException("HALLUCINATION_DETECTED",
                "Response contains ungrounded claims: The CEO's name is Alice Smith");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getBody().getError().getType()).isEqualTo("guardrail_violation");
        assertThat(result.getBody().getError().getCode()).isEqualTo("hallucination_detected");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — GUARDRAIL_PLUGIN_ERROR
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_guardrailPluginError_returns500() {
        HttpServletResponse response = mockResponse("trace-plugin");
        GatewayException ex = new GatewayException("GUARDRAIL_PLUGIN_ERROR",
                "Guardrail plugin 'legal-policy-check' failed and is configured as fail-closed");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody().getError().getType()).isEqualTo("guardrail_plugin_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("guardrail_plugin_error");
    }

    // -------------------------------------------------------------------------
    // Credential error codes
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_credentialNotFound_returns404() {
        HttpServletResponse response = mockResponse("trace-cn");
        GatewayException ex = new GatewayException("CREDENTIAL_NOT_FOUND", "not found: cred-x");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody().getError().getType()).isEqualTo("not_found_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("credential_not_found");
    }

    @Test
    void handleGatewayException_credentialNotRotatable_returns400() {
        HttpServletResponse response = mockResponse("trace-cnr");
        GatewayException ex = new GatewayException("CREDENTIAL_NOT_ROTATABLE",
                "Only ACTIVE credentials can be rotated; credential cred-1 is REVOKED");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
        assertThat(result.getBody().getError().getCode()).isEqualTo("credential_not_rotatable");
    }

    @Test
    void handleGatewayException_credentialStorageModeMismatch_returns400() {
        HttpServletResponse response = mockResponse("trace-csmm");
        GatewayException ex = new GatewayException("CREDENTIAL_STORAGE_MODE_MISMATCH",
                "Credential cred-1 is stored in REFERENCE mode; use the rotation method for that mode");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
    }

    @Test
    void handleGatewayException_credentialReferenceMissing_returns400() {
        HttpServletResponse response = mockResponse("trace-crm");
        GatewayException ex = new GatewayException("CREDENTIAL_REFERENCE_MISSING",
                "secretReference is required for storageMode=REFERENCE");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
    }

    @Test
    void handleGatewayException_credentialApiKeyMissing_returns400() {
        HttpServletResponse response = mockResponse("trace-cakm");
        GatewayException ex = new GatewayException("CREDENTIAL_API_KEY_MISSING",
                "apiKey is required for storageMode=ENCRYPTED");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
    }

    @Test
    void handleGatewayException_invalidStorageMode_returns400() {
        HttpServletResponse response = mockResponse("trace-ism");
        GatewayException ex = new GatewayException("INVALID_STORAGE_MODE",
                "Unknown storageMode 'PLAINTEXT'; expected ENCRYPTED or REFERENCE");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
    }

    @Test
    void handleGatewayException_encryptionNotConfigured_returns400() {
        // A configuration error on the caller's side (an ENCRYPTED credential without
        // GATEWAY_ENCRYPTION_MASTER_PASSWORD set), so a 400 and not a provider error.
        HttpServletResponse response = mockResponse("trace-enc");
        GatewayException ex = new GatewayException("ENCRYPTION_NOT_CONFIGURED",
                "Cannot store ENCRYPTED provider credentials — GATEWAY_ENCRYPTION_MASTER_PASSWORD is not set.");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody().getError().getType()).isEqualTo("invalid_request_error");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — WORKSPACE_CREDENTIAL_REQUIRED (strict-BYOK)
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_tenantCredentialRequired_returns403() {
        HttpServletResponse response = mockResponse("trace-byok");
        GatewayException ex = new GatewayException("WORKSPACE_CREDENTIAL_REQUIRED",
                "Workspace 'acme' has no active provider credential for 'provider.openai.api-key', "
                        + "and strict-BYOK enforcement is on for this workspace. "
                        + "Add a workspace-scoped credential before retrying.");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getBody().getError().getType()).isEqualTo("workspace_credential_required");
        assertThat(result.getBody().getError().getCode()).isEqualTo("workspace_credential_required");
        assertThat(result.getBody().getError().getMessage()).contains("strict-BYOK");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-byok");
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — structured details ride the body under their own keys
    // -------------------------------------------------------------------------

    @Test
    void handleGatewayException_details_areWrittenBesideTheFixedFields() throws Exception {
        HttpServletResponse response = mockResponse("trace-cap");
        // The keys are arbitrary on purpose: the handler writes whatever the exception carries and
        // interprets none of it, so the test uses names that mean nothing to this module.
        GatewayException ex = new GatewayException("LICENSE_TOKEN_CAP_EXCEEDED",
                "Monthly API-call cap exceeded.",
                new java.util.LinkedHashMap<>(java.util.Map.of(
                        "scope", "example", "current_usage", 11200L, "cap", 10000L,
                        "more_info", "https://example.test/limits")));

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(result.getBody().getError().getType()).isEqualTo("token_cap_exceeded");
        assertThat(result.getBody().getError().getDetails())
                .containsEntry("scope", "example").containsEntry("cap", 10000L);

        // flattened, not nested: the keys sit beside message/type/code, and no "details" key exists
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.getBody());
        assertThat(json).contains("\"more_info\":\"https://example.test/limits\"")
                .contains("\"current_usage\":11200").contains("\"scope\":\"example\"")
                .doesNotContain("\"details\"");
    }

    @Test
    void handleGatewayException_noDetails_writesNoExtraKeys() throws Exception {
        HttpServletResponse response = mockResponse("trace-plain");
        var result = handler.handleGatewayException(
                new GatewayException("NO_PROVIDER", "No provider"), mockRequest(), response);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.getBody());
        assertThat(json).doesNotContain("details").doesNotContain("more_info");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static HttpServletRequest mockRequest() {
        return mock(HttpServletRequest.class);
    }

    private static HttpServletResponse mockResponse(String traceId) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getHeader(TraceIdFilter.HEADER)).thenReturn(traceId);
        return response;
    }

    @Test
    void piiRedactUnavailable_carriesTheAttemptedActionSoClientsNeedNotParseTheMessage() {
        // The code names REDACT for compatibility, but only TOKENIZE can produce it. Without this
        // field a client has to read the human-readable message to learn which action failed, which
        // is not a contract.
        HttpServletResponse response = mockResponse("trace-pii-action");
        GatewayException ex = new GatewayException("PII_REDACT_UNAVAILABLE",
                "Request blocked: PII detected and tokenization is unavailable on this pod (SSN)");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getBody().getError().getAttemptedAction()).isEqualTo("TOKENIZE");
        assertThat(result.getBody().getError().getCode()).isEqualTo("pii_redact_unavailable");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-pii-action");
    }

    @Test
    void anErrorCarryingBothKindsOfFieldGetsBoth() {
        // There are two ways to add a field beyond the fixed four, details and attempted action,
        // and an error carrying both must not lose either.
        HttpServletResponse response = mockResponse("trace-both");
        GatewayException ex = new GatewayException("PII_REDACT_UNAVAILABLE",
                "Request blocked: PII detected and tokenization is unavailable on this pod (SSN)",
                java.util.Map.of("entity_types", "SSN"));

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getBody().getError().getAttemptedAction()).isEqualTo("TOKENIZE");
        assertThat(result.getBody().getError().getDetails()).containsEntry("entity_types", "SSN");
        assertThat(result.getBody().getError().getCode()).isEqualTo("pii_redact_unavailable");
        assertThat(result.getBody().getError().getTraceId()).isEqualTo("trace-both");
    }

    @Test
    void everyOtherErrorOmitsTheAttemptedActionEntirely() {
        // NON_NULL keeps it off other responses. A field that appeared everywhere, empty, would be a
        // new thing for every client to ignore.
        HttpServletResponse response = mockResponse("trace-other");
        GatewayException ex = new GatewayException("PII_DETECTED", "blocked");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getBody().getError().getAttemptedAction()).isNull();
    }

    // -------------------------------------------------------------------------
    // handleGatewayException — a suspended workspace, and why
    // -------------------------------------------------------------------------

    /**
     * 403 with the reason as a field. A client telling one kind of suspension from another should
     * not have to parse a sentence that also carries a support address, so the reason rides in the
     * exception's details and the handler writes those into the body.
     */
    @Test
    void handleGatewayException_workspaceSuspended_is403AndNamesTheReason() {
        HttpServletResponse response = mockResponse("trace-susp");
        GatewayException ex = new com.dvarahq.core.exception.WorkspaceSuspendedException(
                "Workspace t1 has been suspended (reason: policy). Contact support.",
                "policy");

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getBody().getError().getType()).isEqualTo("workspace_suspended");
        assertThat(result.getBody().getError().getCode()).isEqualTo("workspace_suspended");
        assertThat(result.getBody().getError().getDetails()).containsEntry("reason", "policy");
    }

    @Test
    void handleGatewayException_workspaceSuspendedWithNoReason_carriesNoReasonField() {
        HttpServletResponse response = mockResponse("trace-susp2");
        GatewayException ex = new com.dvarahq.core.exception.WorkspaceSuspendedException(
                "Workspace t1 has been suspended.", null);

        ResponseEntity<ErrorResponse> result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(result.getBody().getError().getDetails()).isNullOrEmpty();
    }

    @Test
    void handleGatewayException_workspaceCredentialUnavailable_returns503() {
        HttpServletResponse response = mockResponse("trace-credential");
        GatewayException ex = new GatewayException("WORKSPACE_CREDENTIAL_UNAVAILABLE",
                "Workspace 'acme' has a provider credential for 'provider.openai.api-key' that cannot be used");

        var result = handler.handleGatewayException(ex, mockRequest(), response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody().getError().getType()).isEqualTo("workspace_credential_unavailable");
    }

    // Lower-casing with the default locale would turn PII_DETECTED into pıı_detected on a Turkish
    // JVM, and a client switching on error.code would miss it.
    @Test
    void handleGatewayException_lowerCasesTheCodeWhateverTheDefaultLocale() {
        java.util.Locale saved = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            var result = handler.handleGatewayException(
                    new GatewayException("PII_DETECTED", "PII detected"), mockRequest(), mockResponse("trace-locale"));
            assertThat(result.getBody().getError().getCode()).isEqualTo("pii_detected");
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }
}
