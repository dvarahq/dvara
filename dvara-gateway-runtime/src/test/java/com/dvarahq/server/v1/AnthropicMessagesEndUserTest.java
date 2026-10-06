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
package com.dvarahq.server.v1;

import com.dvarahq.core.model.AnthropicMessagesBody;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.server.v1.dto.MessagesRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@code metadata.user_id} on {@code /v1/messages} becomes the request's end user. A plain id does; a JSON
 * object of client identifiers does not, and still goes to Anthropic as the client sent it.
 */
class AnthropicMessagesEndUserTest {

    /** The form some clients send: a JSON string holding a device hash and a session id. */
    private static final String CLIENT_IDS =
            "{\\\"device_id\\\":\\\"9f2c41\\\",\\\"account_uuid\\\":\\\"\\\",\\\"session_id\\\":\\\"5b1e-77\\\"}";

    @Test
    void aPlainId_isTheEndUser() {
        assertThat(toInternal("\"customer-42\"").getUser()).isEqualTo("customer-42");
    }

    @Test
    void aStringHoldingAJsonObject_isNotTheEndUser() {
        assertThat(toInternal("\"" + CLIENT_IDS + "\"").getUser()).isNull();
    }

    @Test
    void aJsonObjectWithSpaceAroundIt_isNotTheEndUser() {
        assertThat(toInternal("\"  {\\\"device_id\\\":\\\"9f2c41\\\"}  \"").getUser()).isNull();
    }

    @Test
    void anObjectSentAsAnObject_isNotTheEndUser() {
        assertThat(toInternal("{\"device_id\": \"9f2c41\"}").getUser()).isNull();
    }

    @Test
    void anIdThatOnlyLooksLikeAnObject_isStillTheEndUser() {
        assertThat(toInternal("\"{team-a}\"").getUser()).isEqualTo("{team-a}");
    }

    @Test
    void aBlankId_isNoEndUser() {
        assertThat(toInternal("\"  \"").getUser()).isNull();
    }

    @Test
    void onAnAnthropicRoute_theMetadataStillGoesAsSent() {
        AnthropicMessagesBody body = body("\"" + CLIENT_IDS + "\"");
        ChatRequest internal = AnthropicMessages.toInternal(body.bind(MessagesRequest.class), null, body);

        assertThat(internal.getUser()).isNull();
        assertThat(body.governedJson(internal)).contains("\"user_id\":\"" + CLIENT_IDS + "\"");
    }

    private static ChatRequest toInternal(String userIdJson) {
        AnthropicMessagesBody body = body(userIdJson);
        return AnthropicMessages.toInternal(body.bind(MessagesRequest.class), null, body);
    }

    private static AnthropicMessagesBody body(String userIdJson) {
        String json = """
                {"model": "claude-sonnet-4-5", "max_tokens": 100,
                 "messages": [{"role": "user", "content": "Hi"}],
                 "metadata": {"user_id": %s}}
                """.formatted(userIdJson);
        return AnthropicMessagesBody.parse(json.getBytes(StandardCharsets.UTF_8));
    }
}
