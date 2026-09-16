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
package com.dvarahq.providers.mock;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseEvaluatorTest {

    private ChatRequest sampleRequest() {
        return ChatRequest.builder()
                .model("mock/test-model")
                .messages(List.of(MultimodalMessage.user("Hello")))
                .build();
    }

    @Test
    void staticString_returnedAsIs() {
        String result = ResponseEvaluator.evaluate("Hello, world!", sampleRequest());
        assertThat(result).isEqualTo("Hello, world!");
    }

    @Test
    void nullConfig_returnsDefault() {
        String result = ResponseEvaluator.evaluate(null, sampleRequest());
        assertThat(result).isEqualTo("This is a mock response");
    }

    @Test
    void groovyScript_evaluatesExpression() {
        String result = ResponseEvaluator.evaluate("groovy: 'Model is ' + request.model", sampleRequest());
        assertThat(result).isEqualTo("Model is mock/test-model");
    }

    @Test
    void groovyScript_accessesRequestFields() {
        String result = ResponseEvaluator.evaluate("groovy: request.model.toUpperCase()", sampleRequest());
        assertThat(result).isEqualTo("MOCK/TEST-MODEL");
    }

    @Test
    void groovyScript_returnsEmptyStringForNullResult() {
        String result = ResponseEvaluator.evaluate("groovy: null", sampleRequest());
        assertThat(result).isEmpty();
    }

    @Test
    void groovyScript_wrapsEvaluationFailureInProviderError() {
        assertThatThrownBy(() ->
                ResponseEvaluator.evaluate("groovy: throw new RuntimeException('boom')", sampleRequest()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Groovy script evaluation failed")
                .hasMessageContaining("boom");
    }

    @Test
    void unprefixedGroovyLikeString_returnedAsPlainText() {
        // No groovy: prefix -> treated as literal static text, even if it looks like code
        String result = ResponseEvaluator.evaluate("request.model.toUpperCase()", sampleRequest());
        assertThat(result).isEqualTo("request.model.toUpperCase()");
    }

    @Test
    void compiledScript_thatDoesNotCompile_failsOnEvaluateAsBefore() {
        ResponseEvaluator evaluator = ResponseEvaluator.compile("groovy: request.model == { unclosed");

        assertThatThrownBy(() -> evaluator.evaluate(sampleRequest()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Groovy script evaluation failed");
    }

    @Test
    void compiledScript_evaluatesEachRequestWithItsOwnBinding() {
        ResponseEvaluator evaluator = ResponseEvaluator.compile("groovy: request.model");
        ChatRequest other = ChatRequest.builder()
                .model("mock/other")
                .messages(List.of(MultimodalMessage.user("Hello")))
                .build();

        assertThat(evaluator.evaluate(sampleRequest())).isEqualTo("mock/test-model");
        assertThat(evaluator.evaluate(other)).isEqualTo("mock/other");
        assertThat(evaluator.source()).isEqualTo("groovy: request.model");
    }
}