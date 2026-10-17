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
import com.dvarahq.core.exception.ProviderStreamFailure;
import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Why a call failed, put on the request for the audit record: the error code, and when the provider gave them,
 * its HTTP status and its own code, and the message on one line. A stream's status is sent with its first event,
 * so for a stream that failed later the record's status says 200 and these are what say why.
 */
public final class CallFailures {

    /** How much of the message is kept. */
    static final int MESSAGE_LIMIT = 300;

    /**
     * Text shaped like a credential: provider keys ({@code sk-}, {@code sk-ant-}, Google's {@code AIza}), a bearer
     * token, and any long unbroken run of key characters. A provider can quote a key back in its error.
     */
    private static final Pattern SECRET = Pattern.compile(
            "\\b(?:sk|pk|rk)-[A-Za-z0-9_*\\-]{8,}"
                    + "|\\bAIza[0-9A-Za-z_\\-]{20,}"
                    + "|(?i:bearer)\\s+[A-Za-z0-9._~+/\\-]{8,}=*"
                    + "|\\b[A-Za-z0-9_\\-]{32,}\\b");

    private CallFailures() {
    }

    /**
     * Records a stream that failed after it started. The message is always kept; the provider's status and code
     * only when the failure reports them, so a stream that broke with no error body has neither.
     */
    public static void recordStream(HttpServletRequest request, Exception failure) {
        request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE,
                failure instanceof GatewayException ge ? ge.getCode() : "STREAM_ERROR");
        putProvider(request, failure);
        String message = mask(failure.getMessage());
        if (message != null) {
            request.setAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE, message);
        }
    }

    /**
     * Records the provider's part of a failure the error handler answers: its status, its code and the message,
     * only when the error came from the provider. A refusal of the gateway's own adds nothing.
     */
    public static void recordProvider(HttpServletRequest request, GatewayException failure) {
        if (failure.getUpstreamStatus() == null) {
            return;
        }
        putProvider(request, failure);
        String message = mask(failure.getMessage());
        if (message != null) {
            request.setAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE, message);
        }
    }

    private static void putProvider(HttpServletRequest request, Exception failure) {
        if (failure instanceof GatewayException ge && ge.getUpstreamStatus() != null) {
            request.setAttribute(AccessLogFilter.ATTR_PROVIDER_STATUS, ge.getUpstreamStatus());
        }
        if (failure instanceof ProviderStreamFailure psf && psf.providerCode() != null) {
            String code = mask(psf.providerCode());
            if (code != null) {
                request.setAttribute(AccessLogFilter.ATTR_PROVIDER_ERROR_CODE, code);
            }
        }
    }

    /** The text on one line, capped, with anything shaped like a key replaced by {@code ***}; null for none. */
    static String mask(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String oneLine = text.replaceAll("\\s+", " ").strip();
        String masked = SECRET.matcher(oneLine).replaceAll("***");
        return masked.length() <= MESSAGE_LIMIT ? masked : masked.substring(0, MESSAGE_LIMIT) + "…";
    }
}
