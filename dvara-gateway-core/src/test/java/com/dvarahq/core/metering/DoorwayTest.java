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
package com.dvarahq.core.metering;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A call's doorway comes from a fixed list, never from the path text. */
class DoorwayTest {

    @ParameterizedTest
    @CsvSource({
            "/v1/chat/completions, CHAT_COMPLETIONS",
            "/v1/completions, COMPLETIONS",
            "/v1/responses, RESPONSES",
            "/v1/messages, MESSAGES",
            "/v1/messages/count_tokens, MESSAGES",
            "/v1/gemini/v1beta/models/gemini-2.5-pro:generateContent, GEMINI",
            "/v1/cursor/chat/completions, CURSOR",
            "/v1/embeddings, EMBEDDINGS",
            "/v1/batches/batch_1/cancel, BATCHES",
            "/v1/models, OTHER",
            "/v1/messagesX, OTHER",
            "/v1/chat/completions-evil, OTHER",
    })
    void eachPathMapsToItsDoorway(String path, Doorway expected) {
        assertThat(Doorway.of(path)).isEqualTo(expected);
    }

    @Test
    void noPathIsOther() {
        assertThat(Doorway.of(null)).isEqualTo(Doorway.OTHER);
    }

    @Test
    void theWireNameIsTheNameInLowerCase_andReadsBack() {
        for (Doorway d : Doorway.values()) {
            assertThat(d.wireName()).isEqualTo(d.name().toLowerCase(java.util.Locale.ROOT));
            assertThat(Doorway.fromWireName(d.wireName())).isEqualTo(d);
        }
    }

    /** A row from before doorways were recorded, or a value this build does not know, has none. */
    @Test
    void anOldOrUnknownValueReadsAsNone() {
        assertThat(Doorway.fromWireName(null)).isNull();
        assertThat(Doorway.fromWireName(" ")).isNull();
        assertThat(Doorway.fromWireName("/v1/chat/completions")).isNull();
    }
}
