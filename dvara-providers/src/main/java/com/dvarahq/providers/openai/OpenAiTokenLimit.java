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
package com.dvarahq.providers.openai;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which field carries a token limit to an OpenAI model.
 *
 * <p>OpenAI's reasoning models — the o-series ({@code o1}, {@code o3}, {@code o4-mini}, ...) and GPT-5
 * and later ({@code gpt-5}, {@code gpt-5.4-mini}, ...) — refuse {@code max_tokens} with a {@code 400} and
 * take {@code max_completion_tokens}, which also counts the tokens the model spends reasoning. Older chat
 * models ({@code gpt-4o}, {@code gpt-4.1}, {@code chatgpt-4o-latest}) keep {@code max_tokens}, so a
 * server that only speaks the older field still works behind an OpenAI base URL.
 *
 * <p>The rule reads the model name, the only thing the gateway knows about the model. On Azure OpenAI
 * that is the deployment name, so it holds when a deployment is named after its model.
 */
public final class OpenAiTokenLimit {

    static final String MAX_TOKENS = "max_tokens";
    static final String MAX_COMPLETION_TOKENS = "max_completion_tokens";

    /** {@code o1}, {@code o3-mini}, {@code o4-mini-2025-04-16}: the letter o and a digit. */
    private static final Pattern O_SERIES = Pattern.compile("o\\d{1,3}(?:[-.].*)?");
    /** {@code gpt-5}, {@code gpt-5.4-mini}, {@code gpt-6}: GPT and a major version. */
    private static final Pattern GPT_VERSION = Pattern.compile("gpt-(\\d{1,3})(?:[-.].*)?");

    private OpenAiTokenLimit() {
    }

    /**
     * The field to send the token limit under for {@code model}, prefix and all ({@code azure/gpt-5} is
     * read as {@code gpt-5}).
     */
    public static String fieldFor(String model) {
        return needsMaxCompletionTokens(model) ? MAX_COMPLETION_TOKENS : MAX_TOKENS;
    }

    static boolean needsMaxCompletionTokens(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String name = model.substring(model.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (O_SERIES.matcher(name).matches()) {
            return true;
        }
        Matcher gpt = GPT_VERSION.matcher(name);
        return gpt.matches() && Integer.parseInt(gpt.group(1)) >= 5;
    }
}
