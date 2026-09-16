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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ContentBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request pieces the chat, completions and Responses doorways read the same way: content parts,
 * images and stop sequences. One reading, so the doorways cannot parse the same input differently.
 */
final class ChatInputs {

    private ChatInputs() {
    }

    /**
     * One part of a chat message's content array, in OpenAI's shape: {@code text} or {@code image_url}.
     * Audio and file parts are refused as unsupported; anything else is not a content part.
     */
    static ContentBlock chatPart(Map<?, ?> part) {
        Object type = part.get("type");
        return switch (type == null ? "" : type.toString()) {
            case "text" -> {
                Object text = part.get("text");
                yield new ContentBlock.TextBlock(text == null ? "" : text.toString());
            }
            case "image_url" -> image(part, "image_url");
            case "input_audio", "file" -> throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    type + " content parts are not supported on /v1/chat/completions (text and image_url only)");
            default -> throw new GatewayException("INVALID_REQUEST", "Unknown content part type: " + type);
        };
    }

    /**
     * An image part: a {@code data:} URL becomes base64 data with its media type, any other URL is carried
     * as-is under {@code image/url} for providers that fetch it, and base64-only providers refuse it.
     */
    static ContentBlock image(Map<?, ?> part, String partName) {
        Object imageUrl = part.get("image_url");
        String url;
        if (imageUrl instanceof Map<?, ?> m) {
            Object u = m.get("url");
            url = u != null ? u.toString() : null;
        } else {
            url = imageUrl != null ? imageUrl.toString() : null;
        }
        if (url == null || url.isBlank()) {
            throw new GatewayException("INVALID_REQUEST", partName + " requires image_url");
        }
        if (url.startsWith("data:")) {
            // data:<mediaType>;base64,<data>
            int comma = url.indexOf(',');
            if (comma < 0) {
                throw new GatewayException("INVALID_REQUEST", "malformed data: image URL");
            }
            String meta = url.substring(5, comma);
            String data = url.substring(comma + 1);
            String mediaType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            return new ContentBlock.ImageBlock(mediaType.isBlank() ? "image/jpeg" : mediaType, data);
        }
        return new ContentBlock.ImageBlock("image/url", url);
    }

    /** OpenAI's {@code stop}: a string or an array of strings. Null when absent. */
    static List<String> stopSequences(Object stop) {
        if (stop == null) {
            return null;
        }
        if (stop instanceof String s) {
            return List.of(s);
        }
        if (stop instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw new GatewayException("INVALID_REQUEST", "stop must be a string or an array of strings");
                }
                out.add(s);
            }
            return out.isEmpty() ? null : out;
        }
        throw new GatewayException("INVALID_REQUEST", "stop must be a string or an array of strings");
    }
}
