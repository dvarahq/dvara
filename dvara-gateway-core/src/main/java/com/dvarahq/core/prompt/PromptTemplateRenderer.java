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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders prompt templates by substituting {@code {{variable}}} placeholders.
 *
 * <h2>What counts as a placeholder, and what happens to something that does not</h2>
 *
 * <p>A placeholder is {@code {{name}}} where the name is letters, digits and underscores, with any
 * surrounding whitespace ignored, so {@code &#123;&#123; name &#125;&#125;} is the same placeholder
 * as {@code &#123;&#123;name&#125;&#125;}. Spaces inside the braces are the natural thing to type,
 * and a placeholder that is not recognised is neither listed as a variable nor substituted, so the
 * braces would travel upstream inside the prompt.
 *
 * <p>Anything else that is {@code &#123;&#123;…&#125;&#125;}-shaped (a hyphen or a dot in the name,
 * a non-ASCII name, a whole sentence) is not a placeholder, and {@link #unrecognisedPlaceholders}
 * finds it so the surfaces that save a template can refuse it there, naming the text, instead of
 * letting it reach a customer as literal braces.
 */
public final class PromptTemplateRenderer {

    /**
     * A placeholder: optional whitespace, a name of word characters, optional whitespace. The
     * whitespace is tolerated rather than significant — the captured group is the name alone, so
     * {@code &#123;&#123; name &#125;&#125;} and {@code &#123;&#123;name&#125;&#125;} are one
     * variable and not two.
     */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    /**
     * Anything that looks like a placeholder, whether or not it is one. Non-greedy, so
     * {@code &#123;&#123;a&#125;&#125; and &#123;&#123;b&#125;&#125;} is two matches rather than one
     * spanning both.
     */
    private static final Pattern PLACEHOLDER_SHAPED = Pattern.compile("\\{\\{.*?}}", Pattern.DOTALL);

    private PromptTemplateRenderer() {}

    /**
     * Renders a template string by replacing all {@code {{key}}} placeholders
     * with values from the variables map.
     *
     * @throws IllegalArgumentException if a placeholder has no corresponding variable value
     */
    public static String render(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        if (variables == null || variables.isEmpty()) {
            // Check if there are any placeholders that need values
            Matcher check = VARIABLE_PATTERN.matcher(template);
            if (check.find()) {
                throw new IllegalArgumentException(
                        "Template variable '{{" + check.group(1) + "}}' has no value");
            }
            return template;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = variables.get(varName);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Template variable '{{" + varName + "}}' has no value");
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Every {@code &#123;&#123;…&#125;&#125;}-shaped run in the template that is <b>not</b> a
     * placeholder, in the order it appears, each as the exact text written.
     *
     * <p>The surfaces that save a template use this to refuse one, because the alternative is that
     * the text is not extracted as a variable, not substituted at render, and delivered to the
     * model as literal braces. The gateway refuses what it cannot honour rather than dropping it.
     *
     * <p>Returns an empty list for a template that is entirely well-formed.
     */
    public static List<String> unrecognisedPlaceholders(String template) {
        if (template == null || template.isEmpty()) {
            return List.of();
        }
        var bad = new ArrayList<String>();
        Matcher shaped = PLACEHOLDER_SHAPED.matcher(template);
        while (shaped.find()) {
            String text = shaped.group();
            if (!VARIABLE_PATTERN.matcher(text).matches() && !bad.contains(text)) {
                bad.add(text);
            }
        }
        return List.copyOf(bad);
    }

    /**
     * Extracts variable names from a template string.
     */
    public static List<String> extractVariables(String template) {
        if (template == null || template.isEmpty()) {
            return List.of();
        }
        var vars = new ArrayList<String>();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!vars.contains(name)) {
                vars.add(name);
            }
        }
        return List.copyOf(vars);
    }
}