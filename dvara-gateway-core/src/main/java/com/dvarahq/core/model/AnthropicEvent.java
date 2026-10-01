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
package com.dvarahq.core.model;

/**
 * One server-sent event of an Anthropic stream, as Anthropic wrote it.
 *
 * <p>Carried on the {@link SseChunk} read from it, so a Messages API caller on a route to Anthropic can be
 * sent the event itself rather than one rebuilt from the chunk. The chunk's text, thinking and tool-call
 * fields are what governance reads; when governance holds or changes them, the caller is sent those instead.</p>
 *
 * @param name   the event name: {@code message_start}, {@code content_block_delta}, {@code ping}, ...
 * @param data   the event's data line, exactly as received
 * @param opaque whether the event belongs to a content block of a type the gateway does not read
 */
public record AnthropicEvent(String name, String data, boolean opaque) {
}
