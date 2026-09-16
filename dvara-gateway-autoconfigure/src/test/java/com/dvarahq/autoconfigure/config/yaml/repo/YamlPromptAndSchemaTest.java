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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import com.dvarahq.core.prompt.PromptTemplateStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Output schemas and prompt templates from {@code gateway.yaml}. Both have consumers in this
 * repository ({@code DefaultOutputSchemaValidator} and {@code DefaultPromptTemplateResolver}), so
 * what the file says changes what the gateway does.
 */
class YamlPromptAndSchemaTest {

    @TempDir
    Path dir;

    private static final String VALID = """
            workspaces:
              - id: acme

            prompt_templates:
              - id: support-reply
                workspace: acme
                system: "You are a support agent for {{company}}."
                template: "Answer this politely: {{question}}"
              - id: support-reply-terse
                workspace: acme
                template: "Answer briefly: {{question}}"
              - id: shared-preamble
                template: "Context: {{context}}"

            output_schemas:
              - id: ticket-triage
                model: "mock*"
                # max_retries and correction_prompt are not fields of the DTO. A file that sets
                # them must keep loading, which is what ignoreUnknown on the DTO buys.
                max_retries: 3
                correction_prompt: "Return only JSON matching the schema."
                schema:
                  type: object
                  properties:
                    severity:
                      type: string
            """;

    private YamlConfigStore store(String yaml) throws IOException {
        Path file = dir.resolve("gateway.yaml");
        Files.writeString(file, yaml);
        GatewayYamlConfig config =
                GatewayYamlLoader.load(Map.of("DVARA_CONFIG_FILE", file.toString())::get).orElseThrow();
        assertThat(GatewayYamlLoader.validate(config))
                .as("the fixture must itself be valid, or the test measures the wrong thing")
                .isEmpty();
        return new YamlConfigStore(config);
    }

    private List<String> errorsIn(String yaml) throws IOException {
        Path file = dir.resolve("invalid.yaml");
        Files.writeString(file, yaml);
        return GatewayYamlLoader.validate(
                GatewayYamlLoader.load(Map.of("DVARA_CONFIG_FILE", file.toString())::get).orElseThrow());
    }

    // --- prompt templates -----------------------------------------------------------------

    @Test
    void templatesAreServedById() throws IOException {
        YamlPromptTemplateRepository repo = new YamlPromptTemplateRepository(store(VALID));

        assertThat(repo.findById("support-reply")).isPresent();
        assertThat(repo.findById("support-reply").orElseThrow().getUserTemplate())
                .isEqualTo("Answer this politely: {{question}}");
        assertThat(repo.findAll()).hasSize(3);
    }

    /**
     * The default matters more than it looks.
     *
     * <p>The API defaults a template to DRAFT, because one is drafted before it is used. A template
     * written into a config file has been decided — and the resolver refuses anything but ACTIVE with
     * {@code PROMPT_TEMPLATE_NOT_ACTIVE}, so defaulting to DRAFT would make every entry in every file
     * unusable until a field nobody knew about was set.
     */
    @Test
    void aTemplateWithNoStatusIsActive() throws IOException {
        assertThat(new YamlPromptTemplateRepository(store(VALID))
                .findById("support-reply").orElseThrow().getStatus())
                .isEqualTo(PromptTemplateStatus.ACTIVE);
    }

    /**
     * Variables are derived from the text, not declared beside it.
     *
     * <p>Two statements of one fact drift, and the failure when they do is a request refused with
     * {@code PROMPT_VARIABLE_MISSING} naming a variable plainly present in the template.
     */
    @Test
    void variablesAreExtractedFromBothHalvesOfTheTemplate() throws IOException {
        assertThat(new YamlPromptTemplateRepository(store(VALID))
                .findById("support-reply").orElseThrow().getVariables())
                .as("{{company}} is in the system prompt, {{question}} in the user template")
                .containsExactly("company", "question");
    }

    @Test
    void aWorkspaceSeesItsOwnTemplatesAndTheGlobalOnes() throws IOException {
        YamlPromptTemplateRepository repo = new YamlPromptTemplateRepository(store(VALID));

        assertThat(repo.findByWorkspaceId("acme")).extracting(t -> t.getId())
                .containsExactlyInAnyOrder("support-reply", "support-reply-terse", "shared-preamble");
        assertThat(repo.findByWorkspaceId("other")).extracting(t -> t.getId())
                .as("a template with no workspace applies everywhere, as a null workspace_id row does")
                .containsExactly("shared-preamble");
    }

    @Test
    void writesAndVersionHistoryAreRefused() throws IOException {
        YamlPromptTemplateRepository repo = new YamlPromptTemplateRepository(store(VALID));

        assertThatThrownBy(() -> repo.deleteById("support-reply")).hasMessageContaining("gateway.yaml");
        assertThatThrownBy(() -> repo.getVersionHistory("support-reply"))
                .as("a file has no history; 'no versions' is a different claim from 'not here'")
                .hasMessageContaining("gateway.yaml");
    }

    // --- output schemas -------------------------------------------------------------------

    @Test
    void schemasAreFoundByTheScopeTheyDeclare() throws IOException {
        YamlOutputSchemaRepository repo = new YamlOutputSchemaRepository(store(VALID));

        assertThat(repo.findByModelPattern("mock*")).hasSize(1);
        assertThat(repo.findByModelPattern("gpt*")).isEmpty();
        assertThat(repo.findById("ticket-triage").orElseThrow().getSchema()).containsKey("properties");
        // The file above sets max_retries and correction_prompt, which are not DTO fields. Loading
        // at all is the assertion: ignoreUnknown on the DTO keeps such a gateway.yaml working.
        assertThat(repo.findById("ticket-triage")).isPresent();
    }

    @Test
    void aSchemaIsEnabledByDefaultAndDisabledOnRequest() throws IOException {
        assertThat(new YamlOutputSchemaRepository(store(VALID)).findByModelPattern("mock*")).hasSize(1);

        YamlOutputSchemaRepository disabled = new YamlOutputSchemaRepository(store("""
                output_schemas:
                  - id: ticket-triage
                    model: "mock*"
                    enabled: false
                    schema:
                      type: object
                """));
        assertThat(disabled.findByModelPattern("mock*"))
                .as("a disabled schema must not enforce a contract the operator switched off")
                .isEmpty();
        assertThat(disabled.findAll())
                .as("but it is still in the file, and findAll is the inventory")
                .hasSize(1);
    }

    /**
     * A schema scoped to neither a model nor a route matches nothing.
     *
     * <p>The gateway refuses this at the API with {@code INVALID_OUTPUT_SCHEMA_SCOPE}. Accepting it
     * from a file would let one surface express what every other rejects — and it would look
     * configured.
     */
    @Test
    void aSchemaWithNoScopeIsRejected() throws IOException {
        assertThat(errorsIn("""
                output_schemas:
                  - id: nowhere
                    schema:
                      type: object
                """))
                .anySatisfy(e -> assertThat(e).contains("must specify 'model', 'route' or both"));
    }

    @Test
    void aSchemaWithNoSchemaIsRejected() throws IOException {
        assertThat(errorsIn("""
                output_schemas:
                  - id: empty
                    model: "gpt*"
                """))
                .anySatisfy(e -> assertThat(e).contains("schema: required field is missing"));
    }

    @Test
    void anUnknownTemplateStatusIsRejected() throws IOException {
        assertThat(errorsIn("""
                prompt_templates:
                  - id: a
                    template: "x"
                    status: PUBLISHED
                """))
                .anySatisfy(e -> assertThat(e).contains("unsupported status 'PUBLISHED'"));
    }
}