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
}
