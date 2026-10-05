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
package com.dvarahq.core.filter;

import java.util.Optional;

/**
 * Asked between the chunks of a streamed answer whether it may go on. A request filter can refuse a call
 * before it reaches a provider; this is how something can end a stream that has already started, for
 * example when access is revoked or an agent's session is ended while the answer is still coming.
 *
 * <p>Register one or more as beans. The gateway asks every check once per chunk, before the chunk is
 * written to the client, so a check must be cheap and must not block: read an in-memory flag, never make
 * a network call. With no check registered nothing is asked and a stream runs exactly as before.</p>
 *
 * <p>When a check answers with a {@link Stop}, the chunk in hand is not written. The client gets one
 * final error event in the shape of its API ({@code data: {"error":{...}}} on chat completions,
 * {@code response.failed} on the Responses API, an {@code error} event on the Messages API), the upstream
 * connection is closed, and the call is recorded like any other that ended in an error: under the stop's
 * code in the access log, the metrics and the audit record, with the tokens already sent metered and
 * billed. A check that throws is logged and skipped for the rest of that stream; it never ends one.</p>
 *
 * <p>A check sees the chunks the client receives. Where the streaming guard holds the whole answer back
 * until the provider has finished, the first check runs when the guard starts delivering.</p>
 */
@FunctionalInterface
public interface StreamStopCheck {

    /**
     * Whether the stream should end now.
     *
     * @return a {@link Stop} to end the stream, or empty to let it go on
     */
    Optional<Stop> check(RunningStream stream);

    /**
     * Who the stream belongs to, taken when it started. Any value may be null when the request did not
     * carry it, such as a stream sent without a session id.
     *
     * @param workspaceId the workspace the API key resolved to
     * @param apiKeyId    the API key's id, never the key itself
     * @param sessionId   the session id the request carried ({@code X-Session-Id})
     * @param traceId     the request's trace id
     * @param model       the model the request asked for, after governance
     * @param path        the request path, such as {@code /v1/chat/completions}
     */
    record RunningStream(String workspaceId, String apiKeyId, String sessionId, String traceId,
                         String model, String path) {
    }

    /**
     * Why the stream ends, as the client and the records see it.
     *
     * @param status  the HTTP status the same refusal would have had before the stream started, such as
     *                {@code 403}. The stream's own status is already {@code 200}; this picks the error type
     *                on APIs whose error event carries one derived from the status.
     * @param code    the error code, in lower snake case, such as {@code session_killed}
     * @param type    the error type on APIs that carry one beside the code; the code when null
     * @param message a message for the client
     */
    record Stop(int status, String code, String type, String message) {

        public Stop {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("A stop needs an error code");
            }
            if (type == null || type.isBlank()) {
                type = code;
            }
            if (message == null) {
                message = "";
            }
        }
    }
}
