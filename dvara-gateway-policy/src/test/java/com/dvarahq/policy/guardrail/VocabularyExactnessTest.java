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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped vocabularies give the same token ids as each vendor's own tokenizer, over texts chosen to reach every
 * branch of the split patterns: prose, code, JSON, numbers, eight scripts, emoji and combining marks, Unicode
 * whitespace, punctuation runs, case changes inside words, empty and one-character text.
 *
 * <p>The expected ids in {@code vocab-reference.json} come from the vendors' tooling, not from this code:
 * {@code scripts/vocab/reference_tokens.py} runs mistral-common's Tekkenizer and Hugging Face tokenizers over the same
 * texts. Ids are compared, not only counts, so two errors that cancel out in a count still fail here.
 */
class VocabularyExactnessTest {

    private static JsonNode reference() throws Exception {
        try (InputStream in = VocabularyExactnessTest.class.getResourceAsStream("vocab-reference.json")) {
            assertThat(in).as("vocab-reference.json on the test classpath").isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    private static List<Integer> ids(JsonNode array, int minus) {
        List<Integer> ids = new ArrayList<>();
        array.forEach(id -> ids.add(id.asInt() - minus));
        return ids;
    }

    @Test
    void tekkenGivesMistralsOwnIds() throws Exception {
        JsonNode ref = reference();
        int offset = ref.get("tekkenSpecialOffset").asInt();
        assertThat(Vocabularies.tekken()).as("the Tekken vocabulary loads").isNotNull();
        for (JsonNode c : ref.get("cases")) {
            String text = c.get("text").asText();
            assertThat(Vocabularies.tekkenIds(text)).as("Tekken ids for %s", text)
                    .isEqualTo(ids(c.get("tekken"), offset));
            assertThat(Vocabularies.tekken().applyAsInt(text)).as("Tekken count for %s", text)
                    .isEqualTo(c.get("tekken").size());
        }
    }

    @Test
    void theDeepSeekVocabularyGivesDeepSeeksOwnIds() throws Exception {
        JsonNode ref = reference();
        assertThat(Vocabularies.deepseek()).as("the DeepSeek vocabulary loads").isNotNull();
        for (JsonNode c : ref.get("cases")) {
            String text = c.get("text").asText();
            assertThat(Vocabularies.deepseekIds(text)).as("DeepSeek ids for %s", text)
                    .isEqualTo(ids(c.get("deepseek"), 0));
            assertThat(Vocabularies.deepseek().applyAsInt(text)).as("DeepSeek count for %s", text)
                    .isEqualTo(c.get("deepseek").size());
        }
    }
}
