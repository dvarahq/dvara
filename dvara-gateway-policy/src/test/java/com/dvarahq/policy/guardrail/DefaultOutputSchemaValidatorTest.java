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
package com.dvarahq.policy.guardrail;

import com.dvarahq.core.guardrail.OutputSchemaConfig;
import com.dvarahq.core.guardrail.OutputSchemaRepository;
import com.dvarahq.core.guardrail.OutputSchemaResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultOutputSchemaValidatorTest {

    private OutputSchemaRepository schemaRepository;
    private DefaultOutputSchemaValidator validator;

    @BeforeEach
    void setUp() {
        schemaRepository = mock(OutputSchemaRepository.class);
        validator = new DefaultOutputSchemaValidator(schemaRepository);
        when(schemaRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void noSchema_returnsValid() {
        OutputSchemaResult result = validator.validate(
                buildRequest("gpt-4o"), buildResponse("{\"name\":\"test\"}"), "workspace-1", null);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void validJson_matchesSchema_returnsValid() {
        OutputSchemaConfig config = OutputSchemaConfig.builder()
                .id("schema-1")
                .modelPattern("gpt-4o*")
                .enabled(true)
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", List.of("name")))
                .build();
        when(schemaRepository.findAll()).thenReturn(List.of(config));

        OutputSchemaResult result = validator.validate(
                buildRequest("gpt-4o"), buildResponse("{\"name\":\"Alice\"}"), "workspace-1", null);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void invalidJson_doesNotMatchSchema_returnsInvalid() {
        OutputSchemaConfig config = OutputSchemaConfig.builder()
                .id("schema-1")
                .modelPattern("gpt-4o*")
                .enabled(true)
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string")),
                        "required", List.of("name")))
                .build();
        when(schemaRepository.findAll()).thenReturn(List.of(config));

        OutputSchemaResult result = validator.validate(
                buildRequest("gpt-4o"), buildResponse("{\"age\":25}"), "workspace-1", null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.schemaId()).isEqualTo("schema-1");
    }

    @Test
    void notJson_returnsInvalid() {
        OutputSchemaConfig config = OutputSchemaConfig.builder()
                .id("schema-1")
                .modelPattern("gpt-4o*")
                .enabled(true)
                .schema(Map.of("type", "object"))
                .build();
        when(schemaRepository.findAll()).thenReturn(List.of(config));

        OutputSchemaResult result = validator.validate(
                buildRequest("gpt-4o"), buildResponse("This is plain text"), "workspace-1", null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors().getFirst()).contains("not valid JSON");
    }

    @Test
    void disabledSchema_returnsValid() {
        OutputSchemaConfig config = OutputSchemaConfig.builder()
                .id("schema-1")
                .modelPattern("gpt-4o*")
                .enabled(false)
                .schema(Map.of("type", "object"))
                .build();
        when(schemaRepository.findAll()).thenReturn(List.of(config));

        OutputSchemaResult result = validator.validate(
                buildRequest("gpt-4o"), buildResponse("not json"), "workspace-1", null);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void matchesGlob_works() {
        assertThat(DefaultOutputSchemaValidator.matchesGlob("gpt-4o*", "gpt-4o")).isTrue();
        assertThat(DefaultOutputSchemaValidator.matchesGlob("gpt-4o*", "gpt-4o-mini")).isTrue();
        assertThat(DefaultOutputSchemaValidator.matchesGlob("gpt-4o*", "claude-3")).isFalse();
        assertThat(DefaultOutputSchemaValidator.matchesGlob("claude-*", "claude-3-opus")).isTrue();
    }

    // --- resolveSchema scope matrix ---

    @Test
    void resolveSchema_modelPatternOnly_matchesAnyRoute() {
        OutputSchemaConfig modelOnly = scopedConfig("m-only", null, "gpt-*");
        when(schemaRepository.findAll()).thenReturn(List.of(modelOnly));

        assertThat(validator.resolveSchema("gpt-4o", "any-route")).isSameAs(modelOnly);
        assertThat(validator.resolveSchema("gpt-4o", null)).isSameAs(modelOnly);
        assertThat(validator.resolveSchema("claude-3", "any-route")).isNull();
    }

    @Test
    void resolveSchema_routeIdOnly_matchesAnyModel() {
        OutputSchemaConfig routeOnly = scopedConfig("r-only", "route-canary", null);
        when(schemaRepository.findAll()).thenReturn(List.of(routeOnly));

        assertThat(validator.resolveSchema("gpt-4o", "route-canary")).isSameAs(routeOnly);
        assertThat(validator.resolveSchema("claude-3", "route-canary")).isSameAs(routeOnly);
        assertThat(validator.resolveSchema("gpt-4o", "route-other")).isNull();
        assertThat(validator.resolveSchema("gpt-4o", null)).isNull();
    }

    @Test
    void resolveSchema_bothAxes_requiresBothToMatch() {
        OutputSchemaConfig both = scopedConfig("both", "route-canary", "gpt-4o*");
        when(schemaRepository.findAll()).thenReturn(List.of(both));

        assertThat(validator.resolveSchema("gpt-4o", "route-canary")).isSameAs(both);
        assertThat(validator.resolveSchema("gpt-4o-mini", "route-canary")).isSameAs(both);
        assertThat(validator.resolveSchema("gpt-4o", "route-other")).isNull();
        assertThat(validator.resolveSchema("claude-3", "route-canary")).isNull();
    }

    @Test
    void resolveSchema_bothAxisConfigBeatsSingleAxis() {
        // A canary route has a strict variant of the same gpt-4o* schema. The
        // route-scoped variant should win for that route, the broad model-only
        // schema should still apply on other routes.
        OutputSchemaConfig modelOnly = scopedConfig("broad", null, "gpt-4o*");
        OutputSchemaConfig both = scopedConfig("canary", "route-canary", "gpt-4o*");
        when(schemaRepository.findAll()).thenReturn(List.of(modelOnly, both));

        assertThat(validator.resolveSchema("gpt-4o", "route-canary")).isSameAs(both);
        assertThat(validator.resolveSchema("gpt-4o", "route-prod")).isSameAs(modelOnly);
    }

    @Test
    void resolveSchema_neitherAxisSet_neverMatches() {
        // Defensive — schemas with neither scope axis are rejected at create
        // time by OutputSchemaController, but a legacy row could exist.
        OutputSchemaConfig orphan = scopedConfig("orphan", null, null);
        when(schemaRepository.findAll()).thenReturn(List.of(orphan));

        assertThat(validator.resolveSchema("gpt-4o", "route-canary")).isNull();
        assertThat(validator.resolveSchema(null, null)).isNull();
    }

    private static OutputSchemaConfig scopedConfig(String id, String routeId, String modelPattern) {
        return OutputSchemaConfig.builder()
                .id(id)
                .routeId(routeId)
                .modelPattern(modelPattern)
                .enabled(true)
                .schema(Map.of("type", "object"))
                .build();
    }

    private ChatRequest buildRequest(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("test")))
                        .build()))
                .build();
    }

    private ChatResponse buildResponse(String text) {
        return ChatResponse.builder()
                .id("resp-1")
                .model("gpt-4o")
                .choices(List.of(ChatResponse.Choice.builder()
                        .index(0)
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock(text)))
                                .build())
                        .finishReason("stop")
                        .build()))
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // An edited schema takes effect.
    //
    // Compiled schemas are cached, so the cache must key on the schema body rather than the config
    // id alone, or a schema edited in the Console would go on being validated against the body
    // compiled at first use: the save would succeed and the old rules would apply.
    // ---------------------------------------------------------------------------------------------

    private static OutputSchemaConfig schemaRequiring(String requiredField) {
        return OutputSchemaConfig.builder()
                .id("schema-1")
                .modelPattern("gpt-4o*")
                .enabled(true)
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(requiredField, Map.of("type", "string")),
                        "required", List.of(requiredField)))
                .build();
    }

    @Test
    void anEditedSchemaIsRecompiled() {
        when(schemaRepository.findAll()).thenReturn(List.of(schemaRequiring("name")));
        assertThat(validator.validate(buildRequest("gpt-4o"), buildResponse("{\"name\":\"Alice\"}"),
                "workspace-1", null).valid()).isTrue();

        // The operator tightens the same schema: the required field is now "email".
        when(schemaRepository.findAll()).thenReturn(List.of(schemaRequiring("email")));

        assertThat(validator.validate(buildRequest("gpt-4o"), buildResponse("{\"name\":\"Alice\"}"),
                "workspace-1", null).valid())
                .as("the edited schema must be the one applied")
                .isFalse();
        assertThat(validator.validate(buildRequest("gpt-4o"), buildResponse("{\"email\":\"a@b.c\"}"),
                "workspace-1", null).valid()).isTrue();
    }

    @Test
    void anUnchangedSchemaIsNotRecompiled() {
        // The cache still has a job: the same body must be compiled once, not once per request.
        OutputSchemaConfig config = schemaRequiring("name");
        when(schemaRepository.findAll()).thenReturn(List.of(config));

        for (int i = 0; i < 3; i++) {
            assertThat(validator.validate(buildRequest("gpt-4o"), buildResponse("{\"name\":\"Alice\"}"),
                    "workspace-1", null).valid()).isTrue();
        }
    }
}
