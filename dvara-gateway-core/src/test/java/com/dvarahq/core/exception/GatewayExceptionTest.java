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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionTest {

    @Test
    void constructorWithCodeAndMessage() {
        GatewayException ex = new GatewayException("NO_PROVIDER", "No provider for model gpt-4o");

        assertThat(ex.getCode()).isEqualTo("NO_PROVIDER");
        assertThat(ex.getMessage()).isEqualTo("No provider for model gpt-4o");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void constructorWithCause() {
        RuntimeException cause = new RuntimeException("connection timeout");
        GatewayException ex = new GatewayException("PROVIDER_ERROR", "Upstream failed", cause);

        assertThat(ex.getCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(ex.getMessage()).isEqualTo("Upstream failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isRuntimeException() {
        GatewayException ex = new GatewayException("TEST", "test");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void describeHttpStatus_returnsHintForKnownCodes() {
        assertThat(GatewayException.describeHttpStatus(401)).contains("invalid API key");
        assertThat(GatewayException.describeHttpStatus(403)).contains("access denied");
        assertThat(GatewayException.describeHttpStatus(404)).contains("not found");
        assertThat(GatewayException.describeHttpStatus(429)).contains("rate limited");
        assertThat(GatewayException.describeHttpStatus(500)).contains("internal server error");
        assertThat(GatewayException.describeHttpStatus(502)).contains("bad gateway");
        assertThat(GatewayException.describeHttpStatus(503)).contains("temporarily unavailable");
    }

    @Test
    void describeHttpStatus_returnsEmptyForUnknownCodes() {
        assertThat(GatewayException.describeHttpStatus(200)).isEmpty();
        assertThat(GatewayException.describeHttpStatus(418)).isEmpty();
        assertThat(GatewayException.describeHttpStatus(599)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // WorkspaceSuspendedException — the reason is a field, not only prose
    // -------------------------------------------------------------------------

    /**
     * A client can read why a workspace is suspended without parsing the message.
     *
     * <p>The message also carries a support address, so telling one kind of suspension from
     * another by string-matching English would be fragile. {@code PII_REDACT_UNAVAILABLE}
     * carries {@code attempted_action} for the same reason.
     */
    @Test
    void workspaceSuspended_carriesTheReasonAsADetail() {
        WorkspaceSuspendedException ex = new WorkspaceSuspendedException(
                "Workspace t1 has been suspended (reason: policy). Contact support.",
                "policy");

        assertThat(ex.getCode()).isEqualTo("WORKSPACE_SUSPENDED");
        assertThat(ex.getDetails()).containsEntry("reason", "policy");
        assertThat(ex.getReason()).isEqualTo("policy");
        assertThat(ex.getMessage())
                .as("the prose keeps it too — a log line should not get worse")
                .contains("policy");
    }

    @Test
    void workspaceSuspended_withNoRecordedReasonCarriesNoField() {
        assertThat(new WorkspaceSuspendedException("Workspace t1 has been suspended.", null)
                .getDetails())
                .as("an absent field is a smaller lie than \"unknown\"")
                .isEmpty();
        assertThat(new WorkspaceSuspendedException("Workspace t1 has been suspended.", "  ")
                .getDetails())
                .isEmpty();
    }
}
