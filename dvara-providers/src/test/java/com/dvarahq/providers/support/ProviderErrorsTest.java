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
package com.dvarahq.providers.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.dvarahq.core.exception.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

class ProviderErrorsTest {

    @Test
    void openAiShape_givesCodeAndMessage() {
        // The body behind "OpenAI API error 400" in #31: the reason nobody could see.
        assertThat(ProviderErrors.reason("""
                {"error": {"message": "Error while downloading file. Upstream status code: 400.",
                           "type": "invalid_request_error", "param": null, "code": "invalid_image_url"}}
                """)).isEqualTo("invalid_image_url: Error while downloading file. Upstream status code: 400.");
    }

    @Test
    void anthropicShape_givesTypeAndMessage() {
        assertThat(ProviderErrors.reason("""
                {"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: must be positive"}}
                """)).isEqualTo("invalid_request_error: max_tokens: must be positive");
    }

    @Test
    void aMessageAlone_isTheReason() {
        assertThat(ProviderErrors.reason("{\"message\":\"The security token included in the request is invalid.\"}"))
                .isEqualTo("The security token included in the request is invalid.");
    }

    @Test
    void notJson_isTheBodyItself_onOneLine() {
        assertThat(ProviderErrors.reason("Bad Gateway\r\n<html>\n  upstream down</html>"))
                .isEqualTo("Bad Gateway <html> upstream down</html>");
        assertThat(ProviderErrors.reason("  ")).isEqualTo("(empty body)");
    }

    @Test
    void aLongReason_isCapped() {
        String r = ProviderErrors.reason("{\"message\":\"" + "x".repeat(1000) + "\"}");
        assertThat(r).hasSize(ProviderErrors.REASON_LIMIT + 1).endsWith("…");
    }

    @Test
    void aResponseIsReadWithoutThrowing() {
        MockClientHttpResponse res = new MockClientHttpResponse(
                "{\"error\":{\"message\":\"bad\",\"code\":\"x\"}}".getBytes(), HttpStatus.BAD_REQUEST);
        assertThat(ProviderErrors.reason(res)).isEqualTo("x: bad");
        ProviderErrors.logRefusal("OpenAI", res);   // a consumed body logs, and never throws
    }

    // ---- what the caller sees ----

    private static MockClientHttpResponse response(HttpStatus status, String body) {
        return new MockClientHttpResponse(body.getBytes(), status);
    }

    @Test
    void a400_carriesTheProvidersCodeAndMessage() {
        GatewayException ex = ProviderErrors.refusal("Groq", response(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"'messages' must contain at least one message\",\"type\":\"invalid_request_error\"}}"),
                "Groq API error");
        assertThat(ex.getMessage()).isEqualTo("Groq API error 400: invalid_request_error: 'messages' must contain at least one message");
        assertThat(ex.getCode()).isEqualTo("PROVIDER_REJECTED_REQUEST");
        assertThat(ex.getUpstreamStatus()).isEqualTo(400);
    }

    @Test
    void a400_keepsWhatComesAfterTheStatus_beforeTheReason() {
        GatewayException ex = ProviderErrors.refusal("Ollama", response(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"registry.ollama.ai/library/gemma does not support tools\"}}"),
                "Ollama error", " for model gemma");
        assertThat(ex.getMessage()).isEqualTo("Ollama error 400 for model gemma: registry.ollama.ai/library/gemma does not support tools");
    }

    @Test
    void a400_withNoMessage_returnsNoneOfTheBody() {
        GatewayException ex = ProviderErrors.refusal("OpenAI",
                response(HttpStatus.BAD_REQUEST, "<html>bad request: {secret prompt}</html>"), "OpenAI API error");
        assertThat(ex.getMessage()).isEqualTo("OpenAI API error 400");
    }

    @Test
    void otherStatuses_returnNoneOfTheBody() {
        String body = "{\"error\":{\"message\":\"Incorrect API key provided: sk-proj-abcdefgh1234.\",\"code\":\"invalid_api_key\"}}";
        assertThat(ProviderErrors.refusal("OpenAI", response(HttpStatus.UNAUTHORIZED, body), "OpenAI API error").getMessage())
                .isEqualTo("OpenAI API error 401 (invalid API key - check your provider credentials)");
        assertThat(ProviderErrors.refusal("OpenAI", response(HttpStatus.INTERNAL_SERVER_ERROR, body), "OpenAI API error").getMessage())
                .isEqualTo("OpenAI API error 500 (provider internal server error)");
    }

    /** Any 4xx about the request carries the provider's reason: only it can say the model is gone. */
    @Test
    void a404_carriesTheProvidersReason() {
        GatewayException ex = ProviderErrors.refusal("Gemini", response(HttpStatus.NOT_FOUND, """
                {"error":{"code":404,"message":"This model models/gemini-2.5-flash is no longer available to new users.","status":"NOT_FOUND"}}
                """), "Gemini streaming error");
        assertThat(ex.getMessage()).startsWith("Gemini streaming error 404")
                .endsWith("NOT_FOUND: This model models/gemini-2.5-flash is no longer available to new users.");
        assertThat(ex.getUpstreamStatus()).isEqualTo(404);
    }

    @Test
    void a422_carriesTheReason_withAKeyMasked() {
        GatewayException ex = ProviderErrors.refusal("OpenAI", response(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"error\":{\"message\":\"Bad value near sk-proj-abcdefgh12345678\",\"code\":\"invalid_value\"}}"),
                "OpenAI API error");
        assertThat(ex.getMessage()).isEqualTo("OpenAI API error 422: invalid_value: Bad value near ***");
    }

    @Test
    void theCallersReason_isCapped_andMasksAKey() {
        assertThat(ProviderErrors.callerReason("{\"message\":\"key sk-proj-abcdefgh12345678 is not allowed here\"}"))
                .isEqualTo("key *** is not allowed here");
        assertThat(ProviderErrors.callerReason("{\"message\":\"" + "y".repeat(1000) + "\"}"))
                .hasSize(ProviderErrors.REASON_LIMIT + 1).endsWith("…");
        assertThat(ProviderErrors.callerReason("not json at all")).isNull();
    }
}
