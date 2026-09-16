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

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The GATEWAY_RESPONSE row for a stream is written on the async dispatch, with what the tail set. */
class AuditResponseFilterTest {

    private final AuditWriter auditWriter = mock(AuditWriter.class);
    private final AuditResponseFilter filter = new AuditResponseFilter(auditWriter);

    @Test
    void aStreamIsAuditedOnItsAsyncDispatch_notAtHandoff() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(AccessLogFilter.ATTR_MODEL, "gpt-4o");
        request.setAttribute("workspaceId", "acme");

        filter.doFilter(request, response, (req, res) -> request.setAsyncStarted(true));
        verifyNoInteractions(auditWriter);

        request.setAsyncStarted(false);
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL, "120");
        request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, "STREAM_ERROR");
        request.setAttribute(AccessLogFilter.ATTR_START_NANOS, System.nanoTime() - 200_000_000L);
        filter.doFilter(request, response, (req, res) -> {});

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter).write(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("GATEWAY_RESPONSE");
        assertThat(event.getValue().payload())
                .containsEntry("provider", "openai")
                .containsEntry("tokens_total", "120")
                .containsEntry("error_code", "STREAM_ERROR");
        assertThat((Long) event.getValue().payload().get("latency_ms")).isGreaterThanOrEqualTo(200L);
    }

    @Test
    void aSynchronousRequestIsAuditedAtItsReturn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        verify(auditWriter).write(org.mockito.ArgumentMatchers.any(AuditEvent.class));
    }

    /** What the chain throws after starting async must propagate; a return inside finally would eat it. */
    @Test
    void anExceptionAfterAsyncStarted_propagatesAndNothingIsAudited() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            request.setAsyncStarted(true);
            throw new IllegalStateException("emitter failed to initialise");
        })).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(auditWriter);
    }
}
