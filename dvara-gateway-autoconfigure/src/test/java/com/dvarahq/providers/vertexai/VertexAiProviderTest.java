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
package com.dvarahq.providers.vertexai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.GeminiGenerateContentBody;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.secret.SecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Gemini on Vertex AI: the Gemini translation, sent to a project and location with a Google access token. One stub
 * stands in for Google's token endpoint, another for Vertex AI.
 */
class VertexAiProviderTest {

    static final TestKeys KEYS = new TestKeys();
    static final String TOKEN_URL = "https://oauth2.test/token";
    static final String BASE = "https://europe-west4-aiplatform.googleapis.com";
    static final String MODELS = BASE + "/v1/projects/my-project/locations/europe-west4/publishers/google/models/";

    private MockRestServiceServer google;
    private MockRestServiceServer vertex;
    private VertexAiProvider provider;

    @BeforeEach
    void setUp() {
        VertexAiProvider.Settings settings = new VertexAiProvider.Settings("my-project", "europe-west4", null, null, TOKEN_URL);
        RestClient.Builder tokenBuilder = RestClient.builder();
        google = MockRestServiceServer.bindTo(tokenBuilder).build();
        RestClient.Builder vertexBuilder = RestClient.builder().baseUrl(VertexAiProvider.baseUrl(settings));
        vertex = MockRestServiceServer.bindTo(vertexBuilder).build();
        SecretProvider secrets = key -> VertexAiCredentials.SECRET.equals(key)
                ? Optional.of(KEYS.serviceAccountJson()) : Optional.empty();
        provider = new VertexAiProvider(settings, vertexBuilder.build(),
                new GoogleAccessTokens(tokenBuilder.build(), TOKEN_URL), new VertexAiCredentials(secrets, null));
    }

    // ---- where a call goes ------------------------------------------------------------------------------------

    @Test
    void eachLocationHasItsOwnEndpoint_andGlobalHasGooglesOne() {
        assertThat(VertexAiProvider.baseUrl(settings("us-central1", null))).isEqualTo("https://us-central1-aiplatform.googleapis.com");
        assertThat(VertexAiProvider.baseUrl(settings("europe-west4", null))).isEqualTo("https://europe-west4-aiplatform.googleapis.com");
        assertThat(VertexAiProvider.baseUrl(settings("global", null))).isEqualTo("https://aiplatform.googleapis.com");
        assertThat(VertexAiProvider.baseUrl(settings(" ", null))).as("blank is global").isEqualTo("https://aiplatform.googleapis.com");
        assertThat(VertexAiProvider.baseUrl(settings("europe-west4", "https://psc.internal.example")))
                .as("an endpoint of the operator's own, such as Private Service Connect").isEqualTo("https://psc.internal.example");
    }

    @Test
    void aLocationThatIsNotOneIsRefused_soItCannotNameAnotherHost() {
        assertThatIllegalArgumentException().isThrownBy(() -> VertexAiProvider.baseUrl(settings("evil.example/x", null)))
                .withMessageContaining("is not a Google Cloud location");
        assertThatIllegalArgumentException().isThrownBy(() -> VertexAiProvider.baseUrl(settings("US-CENTRAL1", null)));
    }

    @Test
    void aProjectIsRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VertexAiProvider(
                        new VertexAiProvider.Settings(" ", "global", null, null, null), RestClient.create(), null, null))
                .withMessage("Vertex AI needs a Google Cloud project");
    }

    @Test
    void itClaimsVertexModels_notTheGeminiApisNames() {
        assertThat(provider.name()).isEqualTo("vertex-ai");
        assertThat(provider.supports(request("vertex/gemini-2.5-flash"))).isTrue();
        assertThat(provider.supports(request("gemini-2.5-flash"))).isFalse();
        assertThat(provider.supportsEmbedding("vertex/text-embedding-005")).isTrue();
        assertThat(provider.supportsEmbedding("vertex/gemini-embedding-001")).isTrue();
        assertThat(provider.supportsEmbedding("vertex/gemini-2.5-flash")).isFalse();
        assertThat(provider.supportsEmbedding("text-embedding-005")).isFalse();
        assertThat(provider.capabilities().supportsStreamingToolCalls()).isTrue();
        assertThat(provider.capabilities().supportsStructuredOutputs()).isTrue();
        assertThat(provider.capabilities().supportsVision()).isTrue();
    }

    // ---- chat --------------------------------------------------------------------------------------------------

    @Test
    void chatGoesToTheProjectAndLocation_withABearerToken_inGeminisShape() {
        expectToken("ya29.vertex");
        vertex.expect(requestTo(MODELS + "gemini-2.5-flash:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ya29.vertex"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("x-goog-api-key"))
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("Hi"))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("Be brief."))
                .andExpect(jsonPath("$.tools[0].functionDeclarations[0].name").value("get_weather"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.model").doesNotExist())
                .andRespond(withSuccess("""
                        {"candidates": [{"content": {"role": "model", "parts": [{"text": "Hello"}]}, "finishReason": "STOP"}],
                         "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 3, "thoughtsTokenCount": 5,
                                           "cachedContentTokenCount": 8, "totalTokenCount": 20}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("vertex/gemini-2.5-flash")
                .messages(List.of(MultimodalMessage.builder().role("system").content(List.of(new ContentBlock.TextBlock("Be brief."))).build(), MultimodalMessage.user("Hi")))
                .tools(List.of(ToolDefinition.builder().name("get_weather").parameters(Map.of("type", "object")).build()))
                .responseFormat(new ResponseFormat.JsonObject())
                .build());

        vertex.verify();
        assertThat(response.getModel()).as("the caller's own name").isEqualTo("vertex/gemini-2.5-flash");
        assertThat(response.getChoices().getFirst().getMessage().getContent()).containsExactly(new ContentBlock.TextBlock("Hello"));
        assertThat(response.getChoices().getFirst().getFinishReason()).isEqualTo("stop");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(12);
        assertThat(response.getUsage().getCompletionTokens()).as("the answer and the thinking").isEqualTo(8);
        assertThat(response.getUsage().getReasoningTokens()).isEqualTo(5);
        assertThat(response.getUsage().getCachedInputTokens()).isEqualTo(8);
    }

    @Test
    void theTokenIsMintedOnce_forManyCalls() {
        expectToken("ya29.once");
        vertex.expect(ExpectedCount.times(3), requestTo(MODELS + "gemini-2.5-pro:generateContent"))
                .andExpect(header("Authorization", "Bearer ya29.once"))
                .andRespond(withSuccess(answer("ok"), MediaType.APPLICATION_JSON));

        for (int i = 0; i < 3; i++) {
            provider.chat(request("vertex/gemini-2.5-pro"));
        }

        google.verify();
        vertex.verify();
    }

    @Test
    void aRouteMaySendTheBareName() {
        expectToken("ya29.bare");
        vertex.expect(requestTo(MODELS + "gemini-2.5-pro:generateContent"))
                .andRespond(withSuccess(answer("ok"), MediaType.APPLICATION_JSON));

        provider.chat(request("gemini-2.5-pro"));
        vertex.verify();
    }

    @Test
    void streamingUsesVertexsSseStream_andReadsUsageAtTheEnd() {
        expectToken("ya29.stream");
        vertex.expect(requestTo(MODELS + "gemini-2.5-flash:streamGenerateContent?alt=sse"))
                .andExpect(header("Authorization", "Bearer ya29.stream"))
                .andRespond(withSuccess("""
                        data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]}

                        data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"lookup","args":{"q":"x"}}}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":2,"thoughtsTokenCount":1,"cachedContentTokenCount":4,"totalTokenCount":10}}

                        """, MediaType.TEXT_EVENT_STREAM));

        List<SseChunk> chunks = drain(provider.streamChat(ChatRequest.builder().model("vertex/gemini-2.5-flash")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build()));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Hel");
        SseChunk last = chunks.get(1);
        assertThat(last.getToolCalls()).singleElement().satisfies(call -> assertThat(call.name()).isEqualTo("lookup"));
        assertThat(last.getFinishReason()).isEqualTo("tool_calls");
        assertThat(last.isDone()).isTrue();
        assertThat(last.getUsage().getPromptTokens()).isEqualTo(7);
        assertThat(last.getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(last.getUsage().getCachedInputTokens()).isEqualTo(4);
        vertex.verify();
    }

    /** A Gemini API body from a Gemini client goes on to Vertex AI as it came, which takes the same body. */
    @Test
    void aGenerateContentBodyIsSentOnAsItCame() {
        expectToken("ya29.pass");
        vertex.expect(requestTo(MODELS + "gemini-2.5-pro:generateContent"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("hello"))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.includeThoughts").value(true))
                .andRespond(withSuccess(answer("hi"), MediaType.APPLICATION_JSON));
        GeminiGenerateContentBody body = GeminiGenerateContentBody.parse("""
                {"contents": [{"role": "user", "parts": [{"text": "hello"}]}],
                 "generationConfig": {"thinkingConfig": {"includeThoughts": true}}}
                """.getBytes(StandardCharsets.UTF_8));

        ChatRequest request = ChatRequest.builder().model("vertex/gemini-2.5-pro").messages(body.messages()).gemini(body).build();
        ChatResponse response = provider.chat(request);

        vertex.verify();
        assertThat(response.getChoices().getFirst().getMessage().getContent()).containsExactly(new ContentBlock.TextBlock("hi"));
        assertThat(request.getGemini().upstreamResponse()).isNotNull();
    }

    @Test
    void countTokensSendsTheFieldsDirectly_withoutAToolConfig() {
        expectToken("ya29.count");
        vertex.expect(requestTo(MODELS + "gemini-2.5-flash:countTokens"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("Hi"))
                .andExpect(jsonPath("$.tools[0].functionDeclarations[0].name").value("get_weather"))
                .andExpect(jsonPath("$.generateContentRequest").doesNotExist())
                .andExpect(jsonPath("$.toolConfig").doesNotExist())
                .andExpect(jsonPath("$.model").doesNotExist())
                .andRespond(withSuccess("{\"totalTokens\": 17, \"totalBillableCharacters\": 40}", MediaType.APPLICATION_JSON));

        assertThat(provider.countInputTokens(ChatRequest.builder().model("vertex/gemini-2.5-flash")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .tools(List.of(ToolDefinition.builder().name("get_weather").build()))
                .toolChoice("required")
                .build())).hasValue(17);
        vertex.verify();
    }

    // ---- errors ------------------------------------------------------------------------------------------------

    @Test
    void aRefusedRequestCarriesVertexsReason() {
        expectToken("ya29.err");
        vertex.expect(requestTo(MODELS + "gemini-9-ultra:generateContent")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":404,\"message\":\"Publisher Model `gemini-9-ultra` was not found.\",\"status\":\"NOT_FOUND\"}}"));

        assertThatThrownBy(() -> provider.chat(request("vertex/gemini-9-ultra")))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.getUpstreamStatus()).isEqualTo(404))
                .hasMessageStartingWith("Vertex AI API error 404")
                .hasMessageContaining("NOT_FOUND: Publisher Model `gemini-9-ultra` was not found.");
    }

    @Test
    void aPermissionRefusalCarriesNoReason_itIsAboutTheGatewaysCredential() {
        expectToken("ya29.denied");
        vertex.expect(requestTo(MODELS + "gemini-2.5-pro:generateContent")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":403,\"message\":\"Permission 'aiplatform.endpoints.predict' denied\",\"status\":\"PERMISSION_DENIED\"}}"));

        assertThatThrownBy(() -> provider.chat(request("vertex/gemini-2.5-pro")))
                .hasMessageStartingWith("Vertex AI API error 403")
                .hasMessageNotContaining("aiplatform.endpoints.predict");
    }

    @Test
    void aStreamRefusedBeforeItStartsIsAnError() {
        expectToken("ya29.s");
        vertex.expect(requestTo(MODELS + "gemini-2.5-pro:streamGenerateContent?alt=sse"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":429,\"message\":\"Resource exhausted.\",\"status\":\"RESOURCE_EXHAUSTED\"}}"));

        assertThatThrownBy(() -> provider.streamChat(ChatRequest.builder().model("vertex/gemini-2.5-pro")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build()))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.getUpstreamStatus()).isEqualTo(429))
                .hasMessageStartingWith("Vertex AI streaming error 429");
    }

    @Test
    void aTokenGoogleRefusesStopsTheCall_beforeVertexIsAsked() {
        google.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}"));

        assertThatThrownBy(() -> provider.chat(request("vertex/gemini-2.5-pro")))
                .hasMessageContaining("invalid_grant: Invalid JWT Signature.");
        vertex.verify();
    }

    // ---- embeddings ----------------------------------------------------------------------------------------------

    @Test
    void textEmbeddingModelsTakeEveryInputInOneCall() {
        expectToken("ya29.embed");
        vertex.expect(requestTo(MODELS + "text-embedding-005:predict"))
                .andExpect(header("Authorization", "Bearer ya29.embed"))
                .andExpect(jsonPath("$.instances[0].content").value("first"))
                .andExpect(jsonPath("$.instances[1].content").value("second"))
                .andExpect(jsonPath("$.parameters.outputDimensionality").value(256))
                .andRespond(withSuccess("""
                        {"predictions": [
                          {"embeddings": {"values": [0.1, 0.2], "statistics": {"token_count": 3, "truncated": false}}},
                          {"embeddings": {"values": [0.3, 0.4], "statistics": {"token_count": 4.0, "truncated": false}}}]}
                        """, MediaType.APPLICATION_JSON));

        EmbeddingResponse response = provider.embed(EmbeddingRequest.builder().model("vertex/text-embedding-005")
                .input(List.of("first", "second")).dimensions(256).build());

        vertex.verify();
        assertThat(response.getModel()).isEqualTo("vertex/text-embedding-005");
        assertThat(response.getData()).extracting(EmbeddingResponse.EmbeddingData::getIndex).containsExactly(0, 1);
        assertThat(response.getData().get(1).getEmbedding()).containsExactly(0.3, 0.4);
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(7);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(7);
    }

    @Test
    void geminiEmbeddingModelsTakeOneInputPerCall() {
        expectToken("ya29.embed");
        vertex.expect(requestTo(MODELS + "gemini-embedding-001:predict"))
                .andExpect(jsonPath("$.instances.length()").value(1))
                .andExpect(jsonPath("$.instances[0].content").value("first"))
                .andExpect(jsonPath("$.parameters").doesNotExist())
                .andRespond(withSuccess(embedding(0.1, 2), MediaType.APPLICATION_JSON));
        vertex.expect(requestTo(MODELS + "gemini-embedding-001:predict"))
                .andExpect(jsonPath("$.instances[0].content").value("second"))
                .andRespond(withSuccess(embedding(0.2, 5), MediaType.APPLICATION_JSON));

        EmbeddingResponse response = provider.embed(EmbeddingRequest.builder().model("vertex/gemini-embedding-001")
                .input(List.of("first", "second")).build());

        vertex.verify();
        assertThat(response.getData()).extracting(EmbeddingResponse.EmbeddingData::getIndex).containsExactly(0, 1);
        assertThat(response.getData().get(1).getEmbedding()).containsExactly(0.2);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(7);
    }

    @Test
    void tokenArraysAreRefused_beforeAnyCall() {
        assertThatThrownBy(() -> provider.embed(EmbeddingRequest.builder().model("vertex/text-embedding-005")
                .input(List.of(List.of(1, 2, 3))).build()))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REQUEST"));
        google.verify();
    }

    @Test
    void anAnswerWithTheWrongNumberOfEmbeddingsIsAnError() {
        expectToken("ya29.embed");
        vertex.expect(requestTo(MODELS + "text-embedding-005:predict"))
                .andRespond(withSuccess("{\"predictions\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.embed(EmbeddingRequest.builder().model("vertex/text-embedding-005")
                .input("one").build()))
                .hasMessage("Vertex AI returned 0 embeddings for 1 inputs");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private void expectToken(String value) {
        google.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"" + value + "\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    }

    private static VertexAiProvider.Settings settings(String location, String baseUrl) {
        return new VertexAiProvider.Settings("p", location, baseUrl, null, null);
    }

    private static ChatRequest request(String model) {
        return ChatRequest.builder().model(model).messages(List.of(MultimodalMessage.user("Hi"))).build();
    }

    private static String answer(String text) {
        return "{\"candidates\": [{\"content\": {\"role\": \"model\", \"parts\": [{\"text\": \"" + text
                + "\"}]}, \"finishReason\": \"STOP\"}]}";
    }

    private static String embedding(double value, int tokens) {
        return "{\"predictions\": [{\"embeddings\": {\"values\": [" + value + "], \"statistics\": {\"token_count\": "
                + tokens + "}}}]}";
    }

    private static List<SseChunk> drain(Iterator<SseChunk> iterator) {
        List<SseChunk> out = new ArrayList<>();
        iterator.forEachRemaining(out::add);
        return out;
    }
}
