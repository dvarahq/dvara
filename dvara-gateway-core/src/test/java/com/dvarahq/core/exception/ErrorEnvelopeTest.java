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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorEnvelopeTest {

    @Test
    void theMessagesDoorwayAndWhatIsUnderItAnswerInAnthropicsEnvelope() {
        assertThat(ErrorEnvelope.anthropic("/v1/messages")).isTrue();
        assertThat(ErrorEnvelope.anthropic("/v1/messages/count_tokens")).isTrue();
        assertThat(ErrorEnvelope.anthropic("/v1/messagesx")).isFalse();
        assertThat(ErrorEnvelope.anthropic("/v1/chat/completions")).isFalse();
        assertThat(ErrorEnvelope.anthropic(null)).isFalse();
    }

    @Test
    void anthropicsEnvelopeCarriesTheStatusTypeTheCodeAndTheRequestId() {
        Map<String, Object> body = ErrorEnvelope.body("/v1/messages", 429, "slow down", "rate_limit_exceeded",
                "rate_limit_error", "trace-1", Map.of("rate_limit", Map.of("limit", 1)));

        assertThat(body).containsEntry("type", "error").containsEntry("request_id", "trace-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertThat(error).containsEntry("type", "rate_limit_error").containsEntry("message", "slow down")
                .containsEntry("code", "rate_limit_exceeded").containsKey("rate_limit");
    }

    @Test
    void everyOtherDoorwayKeepsTheOpenAiEnvelope() {
        Map<String, Object> body = ErrorEnvelope.body("/v1/chat/completions", 401, "no key", "api_key_required",
                "authentication_error", null, null);

        assertThat(body).containsOnlyKeys("error");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        assertThat(error).containsEntry("type", "authentication_error").containsEntry("code", "api_key_required")
                .containsEntry("trace_id", "");
    }

    @Test
    void theAnthropicTypeFollowsTheStatus() {
        assertThat(ErrorEnvelope.anthropicType(401)).isEqualTo("authentication_error");
        assertThat(ErrorEnvelope.anthropicType(403)).isEqualTo("permission_error");
        assertThat(ErrorEnvelope.anthropicType(429)).isEqualTo("rate_limit_error");
        assertThat(ErrorEnvelope.anthropicType(503)).isEqualTo("overloaded_error");
        assertThat(ErrorEnvelope.anthropicType(502)).isEqualTo("api_error");
    }
}
