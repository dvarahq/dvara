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

import com.dvarahq.core.exception.ErrorEnvelope;
import com.dvarahq.core.util.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Map;

/** How a servlet filter refuses a request: in the envelope of the doorway it was made on ({@link ErrorEnvelope}). */
public final class Refusals {

    private Refusals() {
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
                ErrorEnvelope.body(request.getRequestURI(), status, message, code, type, traceId, extra)));
    }
}
