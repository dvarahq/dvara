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

import com.dvarahq.core.guardrail.ImageTokens;
import com.dvarahq.core.guardrail.TokenEstimation;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * BPE tokenizer using jtokkit for accurate token counting. Model-aware encoding resolution with
 * cached tokenizer instances.
 *
 * <p>The encoding registry is static on purpose. Each registry materializes the cl100k_base rank
 * table (about 100,000 entries) eagerly in its constructor, so a per-instance registry would be
 * duplicated for every Spring context that wires this bean. The table is immutable reference data
 * and jtokkit encodings are thread-safe, so one copy per classloader is the right amount to keep.
 */
public class TiktokenEstimator implements TokenEstimator {

    /**
     * Bound on {@link #encodingCache}. The key is the caller-supplied model string off the request,
     * so an unbounded cache grows with whatever a client chooses to send. Past this many distinct
     * models the cache stops growing and resolution simply runs each time — slower, and bounded.
     * Comfortably above the real {@link ModelType} vocabulary, so no genuine model ever misses.
     */
    private static final int MAX_CACHED_MODELS = 256;

    private static final EncodingRegistry REGISTRY = Encodings.newLazyEncodingRegistry();
    private static final Encoding DEFAULT_ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    private final ConcurrentHashMap<String, Encoding> encodingCache = new ConcurrentHashMap<>();

    /**
     * Every part of the request that becomes tokens is counted, not only message text. Tool results
     * are usually the bulk of a function-calling conversation and the tool definitions are sent on
     * every turn, and the context-window governor reads this number, so counting text blocks alone
     * would under-count precisely the requests most likely to overflow a window.
     *
     * <p>An image is counted by its provider's published formula, from the size in its header (see
     * {@link ImageTokens}), never by running BPE over its base64 payload: a provider does not
     * tokenize the bytes, so that would give a large number with no relationship to the cost.</p>
     *
     * <p>The switch over the content block kinds has no default on purpose. A new kind of block is a
     * compile error here, rather than a part of the request that silently counts as nothing.</p>
     */
    @Override
    public int estimateTokens(ChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return 0;
        }
        Encoding encoding = resolveEncoding(request.getModel());
        int total = 0;
        for (var message : request.getMessages()) {
            // Per-message overhead (role + separators)
            total += 4;
            if (message.getContent() != null) {
                for (var block : message.getContent()) {
                    total += switch (block) {
                        case ContentBlock.TextBlock text -> countTokens(encoding, text.text());
                        case ContentBlock.ImageBlock image -> ImageTokens.estimate(image, request.getModel());
                        case ContentBlock.ThinkingBlock thinking -> countTokens(encoding, thinking.thinking());
                        case ContentBlock.RedactedThinkingBlock redacted -> countTokens(encoding, redacted.data());
                        case null -> 0;
                    };
                }
            }
            // A tool call the model made is replayed to the provider on the next turn, name and
            // arguments both, so it costs context exactly as message text does.
            if (message.getToolCalls() != null) {
                for (var toolCall : message.getToolCalls()) {
                    total += countTokens(encoding, toolCall.getName());
                    total += countTokens(encoding, toolCall.getArguments());
                }
            }
        }
        // The tool definitions travel with every turn, and a handful of JSON schemas is routinely
        // larger than the conversation they accompany.
        total += countTokens(encoding, TokenEstimation.renderTools(request.getTools()));
        // Reply priming overhead
        total += 3;
        return total;
    }

    /**
     * The same parts of the request as {@link #estimateTokens(ChatRequest)}, with each text's UTF-8 length in place of
     * its BPE count. Every token of a byte-level BPE encoding stands for at least one byte, so a text never has more
     * tokens than bytes, and this never comes out below the count. Measuring a text's length is a small fraction of
     * counting it, which is what lets the context-window check skip the count for a request well inside the window.
     */
    @Override
    public int upperBound(ChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return 0;
        }
        long total = 0;
        for (var message : request.getMessages()) {
            total += 4;
            if (message.getContent() != null) {
                for (var block : message.getContent()) {
                    total += switch (block) {
                        case ContentBlock.TextBlock text -> utf8Length(text.text());
                        case ContentBlock.ImageBlock image -> ImageTokens.estimate(image, request.getModel());
                        case ContentBlock.ThinkingBlock thinking -> utf8Length(thinking.thinking());
                        case ContentBlock.RedactedThinkingBlock redacted -> utf8Length(redacted.data());
                        case null -> 0;
                    };
                }
            }
            if (message.getToolCalls() != null) {
                for (var toolCall : message.getToolCalls()) {
                    total += utf8Length(toolCall.getName());
                    total += utf8Length(toolCall.getArguments());
                }
            }
        }
        total += utf8Length(TokenEstimation.renderTools(request.getTools()));
        total += 3;
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /** The text's length in UTF-8 bytes, without encoding it. */
    static long utf8Length(String text) {
        if (text == null) {
            return 0;
        }
        long bytes = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                // Includes a lone surrogate, which the encoder writes as a three-byte replacement.
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * Counted as ordinary text. A caller's text can contain a special token's spelling, {@code <|endoftext|>} for one,
     * which the plain count refuses with an exception; a provider reads it as text, and so does this.
     */
    private static int countTokens(Encoding encoding, String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokensOrdinary(text);
    }

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return DEFAULT_ENCODING.countTokensOrdinary(text);
    }

    private Encoding resolveEncoding(String model) {
        if (model == null) {
            return DEFAULT_ENCODING;
        }
        Encoding cached = encodingCache.get(model);
        if (cached != null) {
            return cached;
        }
        Encoding resolved = lookup(model);
        // Bounded on purpose: the key comes from the request body, so a client sending endlessly
        // varied model names would otherwise grow this map for the life of the process.
        if (encodingCache.size() < MAX_CACHED_MODELS) {
            encodingCache.putIfAbsent(model, resolved);
        }
        return resolved;
    }

    private static Encoding lookup(String model) {
        try {
            ModelType modelType = ModelType.fromName(model).orElse(null);
            if (modelType != null) {
                return REGISTRY.getEncodingForModel(modelType);
            }
        } catch (Exception ignored) {
            // Fall through to default
        }
        // For unknown models (claude, etc.), use cl100k_base as a reasonable default
        return DEFAULT_ENCODING;
    }
}