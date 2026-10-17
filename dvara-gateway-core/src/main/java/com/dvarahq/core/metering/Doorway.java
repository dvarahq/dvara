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

import java.util.Locale;

/**
 * The API a call came in on. Usage rows and audit records carry it so spend can be read per API, and so per
 * coding agent, since each agent uses its own: Codex the Responses API, Claude Code the Messages API, Gemini CLI
 * the Gemini API, Cursor its own path.
 *
 * <p>A fixed list, never the raw path, so no text the caller chose lands in a usage column. A row written before
 * the gateway recorded this has none, and readers show it as unknown.
 */
public enum Doorway {

    CHAT_COMPLETIONS("/v1/chat/completions"),
    COMPLETIONS("/v1/completions"),
    RESPONSES("/v1/responses"),
    MESSAGES("/v1/messages"),
    GEMINI("/v1/gemini/"),
    CURSOR("/v1/cursor/"),
    EMBEDDINGS("/v1/embeddings"),
    BATCHES("/v1/batches"),
    /** A path on the model API that is none of the above. */
    OTHER(null);

    private final String prefix;

    Doorway(String prefix) {
        this.prefix = prefix;
    }

    /** The value written to a usage row or an audit record: the name in lower case, such as {@code responses}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The doorway a request path belongs to; {@link #OTHER} for any other path, including null. */
    public static Doorway of(String path) {
        if (path != null) {
            for (Doorway d : values()) {
                if (d.prefix != null && matches(path, d.prefix)) {
                    return d;
                }
            }
        }
        return OTHER;
    }

    /**
     * The doorway a stored value names, or null for none: a row from before doorways were recorded, or a value
     * this build does not know.
     */
    public static Doorway fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (Doorway d : values()) {
            if (d.wireName().equalsIgnoreCase(value.trim())) {
                return d;
            }
        }
        return null;
    }

    private static boolean matches(String path, String prefix) {
        if (prefix.endsWith("/")) {
            return path.startsWith(prefix);
        }
        // /v1/messages and /v1/messages/count_tokens, but not /v1/messagesX
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
