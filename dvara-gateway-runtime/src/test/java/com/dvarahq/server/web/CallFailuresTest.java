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
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** What a failed call puts on the request for its audit record. */
class CallFailuresTest {

    @Test
    void aProviderThatEndsItsStreamWithAnError_givesItsStatusCodeAndMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CallFailures.recordStream(request, new ProviderStreamFailure(429,
                "Your credit balance is too low", "credit_balance_exhausted", null));

        assertThat(request.getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isEqualTo("PROVIDER_ERROR");
        assertThat(request.getAttribute(AccessLogFilter.ATTR_PROVIDER_STATUS)).isEqualTo(429);
        assertThat(request.getAttribute(AccessLogFilter.ATTR_PROVIDER_ERROR_CODE)).isEqualTo("credit_balance_exhausted");
        assertThat(request.getAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE)).isEqualTo("Your credit balance is too low");
    }

    /** A stream that broke with no error body says it broke, and names no provider status or code. */
    @Test
    void aStreamThatBrokeWithNoErrorBody_hasNoProviderReason() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CallFailures.recordStream(request, new GatewayException("PROVIDER_ERROR",
                "The upstream stream ended before it finished; the answer is incomplete"));

        assertThat(request.getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isEqualTo("PROVIDER_ERROR");
        assertThat(request.getAttribute(AccessLogFilter.ATTR_PROVIDER_STATUS)).isNull();
        assertThat(request.getAttribute(AccessLogFilter.ATTR_PROVIDER_ERROR_CODE)).isNull();
        assertThat((String) request.getAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE)).contains("ended before it finished");
    }

    @Test
    void aFailureThatIsNotTheGatewaysOwn_isAStreamError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CallFailures.recordStream(request, new java.io.IOException("Connection reset"));

        assertThat(request.getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isEqualTo("STREAM_ERROR");
        assertThat(request.getAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE)).isEqualTo("Connection reset");
    }

    @Test
    void anythingShapedLikeAKeyIsMasked() {
        assertThat(CallFailures.mask("Incorrect API key provided: sk-proj-abcdefgh12345678. Check it."))
                .isEqualTo("Incorrect API key provided: ***. Check it.")
                .doesNotContain("abcdefgh");
        assertThat(CallFailures.mask("bad key sk-ant-api03-XYZxyz0123456789")).isEqualTo("bad key ***");
        assertThat(CallFailures.mask("key AIzaSyA1234567890abcdefghijklmnop rejected")).isEqualTo("key *** rejected");
        assertThat(CallFailures.mask("Authorization: Bearer eyJhbGciOi.payload.sig was refused"))
                .isEqualTo("Authorization: *** was refused");
        assertThat(CallFailures.mask("token 0123456789abcdef0123456789abcdef0123 leaked")).isEqualTo("token *** leaked");
    }

    @Test
    void theProviderCodeIsMaskedToo() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CallFailures.recordStream(request, new ProviderStreamFailure(401, "no", "sk-live-abcdefgh12345678", null));
        assertThat(request.getAttribute(AccessLogFilter.ATTR_PROVIDER_ERROR_CODE)).isEqualTo("***");
    }

    @Test
    void theMessageIsOneLineAndCapped() {
        String masked = CallFailures.mask("line one\nline two\t" + "word ".repeat(200));
        assertThat(masked).doesNotContain("\n").startsWith("line one line two word");
        assertThat(masked.length()).isLessThanOrEqualTo(CallFailures.MESSAGE_LIMIT + 1);
    }

    /** The error handler adds the provider's part only when the provider is what failed. */
    @Test
    void theHandlerRecordsOnlyAProvidersFailure() {
        MockHttpServletRequest provider = new MockHttpServletRequest();
        CallFailures.recordProvider(provider, GatewayException.upstream(503, "Provider returned 503"));
        assertThat(provider.getAttribute(AccessLogFilter.ATTR_PROVIDER_STATUS)).isEqualTo(503);
        assertThat(provider.getAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE)).isEqualTo("Provider returned 503");

        MockHttpServletRequest own = new MockHttpServletRequest();
        CallFailures.recordProvider(own, new GatewayException("BUDGET_EXCEEDED", "Budget exceeded"));
        assertThat(own.getAttribute(AccessLogFilter.ATTR_PROVIDER_STATUS)).isNull();
        assertThat(own.getAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE)).isNull();
    }
}
