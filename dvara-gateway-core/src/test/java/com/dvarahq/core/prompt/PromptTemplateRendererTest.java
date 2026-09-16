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
package com.dvarahq.core.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateRendererTest {

    @Test
    void render_substitutesVariables() {
        String result = PromptTemplateRenderer.render(
                "Hello {{name}}, welcome to {{company}}!",
                Map.of("name", "Alice", "company", "Acme"));
        assertThat(result).isEqualTo("Hello Alice, welcome to Acme!");
    }

    @Test
    void render_multipleOccurrencesOfSameVariable() {
        String result = PromptTemplateRenderer.render(
                "{{name}} said: I am {{name}}",
                Map.of("name", "Bob"));
        assertThat(result).isEqualTo("Bob said: I am Bob");
    }

    @Test
    void render_noVariables_returnsUnchanged() {
        String result = PromptTemplateRenderer.render("No variables here", Map.of());
        assertThat(result).isEqualTo("No variables here");
    }

    @Test
    void render_missingVariable_throws() {
        assertThatThrownBy(() ->
                PromptTemplateRenderer.render("Hello {{name}}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void render_nullTemplate_returnsNull() {
        assertThat(PromptTemplateRenderer.render(null, Map.of())).isNull();
    }

    @Test
    void render_emptyTemplate_returnsEmpty() {
        assertThat(PromptTemplateRenderer.render("", Map.of())).isEmpty();
    }

    @Test
    void render_specialRegexCharsInValue() {
        String result = PromptTemplateRenderer.render(
                "Query: {{query}}", Map.of("query", "price is $100 (USD)"));
        assertThat(result).isEqualTo("Query: price is $100 (USD)");
    }

    @Test
    void extractVariables_findsAll() {
        List<String> vars = PromptTemplateRenderer.extractVariables(
                "Hello {{name}}, your order {{orderId}} for {{name}}");
        assertThat(vars).containsExactly("name", "orderId");
    }

    @Test
    void extractVariables_noVariables() {
        assertThat(PromptTemplateRenderer.extractVariables("no vars")).isEmpty();
    }

    @Test
    void extractVariables_null_returnsEmpty() {
        assertThat(PromptTemplateRenderer.extractVariables(null)).isEmpty();
    }

    // --- what counts as a placeholder ------------------------------------------------

    /**
     * Spaces inside the braces are the natural thing to type. The placeholder must be extracted and
     * substituted like an unspaced one, rather than delivered to the model as literal braces.
     */
    @Test
    void spacesInsideTheBracesAreStillTheSamePlaceholder() {
        assertThat(PromptTemplateRenderer.render("Hello {{ name }}", java.util.Map.of("name", "Ada")))
                .isEqualTo("Hello Ada");
        assertThat(PromptTemplateRenderer.extractVariables("Hello {{ name }}"))
                .containsExactly("name");
    }

    @Test
    void aSpacedAndAnUnspacedPlaceholderAreOneVariableNotTwo() {
        assertThat(PromptTemplateRenderer.extractVariables("{{name}} and {{ name }}"))
                .containsExactly("name");
    }

    @Test
    void aMissingValueForASpacedPlaceholderIsStillRefused() {
        assertThatThrownBy(() -> PromptTemplateRenderer.render("Hello {{ name }}", java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    // --- and what does not ------------------------------------------------------------

    @Test
    void braceShapedTextThatIsNotAPlaceholderIsReportedRatherThanPassedThrough() {
        // Each of these is left in the prompt verbatim if nobody looks, which is a customer reading
        // the braces back in a reply. The save surfaces refuse on this list.
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders("Hi {{user-name}}"))
                .containsExactly("{{user-name}}");
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders("Hi {{user.name}}"))
                .containsExactly("{{user.name}}");
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders("Hi {{a whole sentence}}"))
                .containsExactly("{{a whole sentence}}");
    }

    @Test
    void aWellFormedTemplateReportsNothing() {
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders("Hi {{name}}, from {{ city }}"))
                .isEmpty();
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders("no placeholders here")).isEmpty();
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders(null)).isEmpty();
    }

    @Test
    void twoRunsAreTwoFindingsNotOneSpanningBoth() {
        // Non-greedy: a greedy match would swallow the text between them and report one giant run.
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders("{{a-1}} then {{b-2}}"))
                .containsExactly("{{a-1}}", "{{b-2}}");
    }

    @Test
    void theSameBadRunTwiceIsReportedOnce() {
        assertThat(PromptTemplateRenderer.unrecognisedPlaceholders("{{a-1}} and {{a-1}}"))
                .containsExactly("{{a-1}}");
    }
}
