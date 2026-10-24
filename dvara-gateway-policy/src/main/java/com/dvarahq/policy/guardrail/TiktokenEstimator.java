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
import com.dvarahq.core.guardrail.TokenBreakdown;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

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
    private static final Encoding O200K_ENCODING = REGISTRY.getEncoding(EncodingType.O200K_BASE);

    private static final ToIntFunction<String> DEFAULT_COUNTER = DEFAULT_ENCODING::countTokensOrdinary;
    private static final ToIntFunction<String> O200K_COUNTER = O200K_ENCODING::countTokensOrdinary;

    /**
     * How a model's text is counted, the correction its family's count is multiplied by, and what tools add; with the
     * vocabulary's name and whether it is the model's own, for a caller that shows how a count was made.
     */
    private record Counting(ToIntFunction<String> counter, double correction, int toolOverhead, int requestOverhead,
                            String encoding, boolean exact) {

        static final Counting DEFAULT = new Counting(DEFAULT_COUNTER, 1.0, 0, 0, CL100K, false);
    }

    private static final String CL100K = EncodingType.CL100K_BASE.getName();

    private final ConcurrentHashMap<String, Counting> encodingCache = new ConcurrentHashMap<>();
    private final TokenizerResolver resolver;

    /** Each model counted by its family (see {@link DefaultTokenizerResolver}), with the measured corrections. */
    public TiktokenEstimator() {
        this(new DefaultTokenizerResolver());
    }

    /** @param resolver which tokenizer counts a model, and its family's correction */
    public TiktokenEstimator(TokenizerResolver resolver) {
        this.resolver = resolver == null ? new DefaultTokenizerResolver() : resolver;
    }

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
        Counting counting = resolveEncoding(request.getModel());
        ToIntFunction<String> encoding = counting.counter();
        int total = 0;
        // Images are counted by the provider's own formula, so the family's correction, which is for text, skips them.
        long images = 0;
        for (var message : request.getMessages()) {
            // Per-message overhead (role + separators)
            total += 4;
            if (message.getContent() != null) {
                for (var block : message.getContent()) {
                    if (block instanceof ContentBlock.ImageBlock image) {
                        images += ImageTokens.estimate(image, request.getModel());
                        continue;
                    }
                    total += switch (block) {
                        case ContentBlock.TextBlock text -> countTokens(encoding, text.text());
                        case ContentBlock.ImageBlock image -> 0;
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
        return corrected(total, counting.correction(), images + fixedOverhead(request, counting));
    }

    /**
     * The same count as {@link #estimateTokens(ChatRequest)}, part by part. Each part is its text's count times the
     * family's correction, rounded down; {@code overhead} takes the per-message and priming tokens, the tool overhead
     * and what rounding left, so the parts add up to the total exactly.
     */
    @Override
    public Optional<TokenBreakdown> breakdown(ChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return Optional.empty();
        }
        Counting counting = resolveEncoding(request.getModel());
        ToIntFunction<String> encoding = counting.counter();
        long system = 0;
        long user = 0;
        long assistant = 0;
        long toolResults = 0;
        long images = 0;
        long framing = 0;
        List<Integer> perMessage = new ArrayList<>(request.getMessages().size());
        for (var message : request.getMessages()) {
            framing += 4;
            long text = 0;
            if (message.getContent() != null) {
                for (var block : message.getContent()) {
                    if (block instanceof ContentBlock.ImageBlock image) {
                        images += ImageTokens.estimate(image, request.getModel());
                        continue;
                    }
                    text += switch (block) {
                        case ContentBlock.TextBlock t -> countTokens(encoding, t.text());
                        case ContentBlock.ImageBlock image -> 0;
                        case ContentBlock.ThinkingBlock thinking -> countTokens(encoding, thinking.thinking());
                        case ContentBlock.RedactedThinkingBlock redacted -> countTokens(encoding, redacted.data());
                        case null -> 0;
                    };
                }
            }
            if (message.getToolCalls() != null) {
                for (var toolCall : message.getToolCalls()) {
                    text += countTokens(encoding, toolCall.getName());
                    text += countTokens(encoding, toolCall.getArguments());
                }
            }
            perMessage.add(scaled(text, counting.correction()));
            switch (part(message.getRole())) {
                case SYSTEM -> system += text;
                case ASSISTANT -> assistant += text;
                case TOOL -> toolResults += text;
                case USER -> user += text;
            }
        }
        long tools = countTokens(encoding, TokenEstimation.renderTools(request.getTools()));
        framing += 3;
        int total = corrected(system + user + assistant + toolResults + tools + framing, counting.correction(),
                images + fixedOverhead(request, counting));
        double c = counting.correction();
        int s = scaled(system, c);
        int u = scaled(user, c);
        int a = scaled(assistant, c);
        int t = scaled(tools, c);
        int r = scaled(toolResults, c);
        int i = (int) Math.min(images, Integer.MAX_VALUE);
        long overhead = (long) total - s - u - a - t - r - i;
        return Optional.of(new TokenBreakdown(s, u, a, t, r, i, (int) Math.max(0, overhead), perMessage,
                counting.encoding(), counting.exact()));
    }

    /** The part of the request a message's tokens belong to. */
    private enum Part { SYSTEM, USER, ASSISTANT, TOOL }

    private static Part part(String role) {
        if (role == null) {
            return Part.USER;
        }
        return switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case "system", "developer" -> Part.SYSTEM;
            case "assistant", "model" -> Part.ASSISTANT;
            case "tool", "function" -> Part.TOOL;
            default -> Part.USER;
        };
    }

    /** A part's count times the correction, rounded down, so the parts never add up to more than the total. */
    private static int scaled(long count, double correction) {
        long value = correction == 1.0 ? count : (long) Math.floor(count * correction);
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    /**
     * The provider's own tokens around the request, and its text in front of the tools when the request has any. Both
     * are fixed per family, so the family's correction, which is for text, does not scale them.
     */
    private static int fixedOverhead(ChatRequest request, Counting counting) {
        int tools = request.getTools() == null || request.getTools().isEmpty() ? 0 : counting.toolOverhead();
        return counting.requestOverhead() + tools;
    }

    /**
     * The text's count times the family's correction, rounded up so a correction never lowers a bound to zero, plus
     * what is counted apart from the text: images and the tool overhead.
     */
    private static int corrected(long count, double correction, long overhead) {
        long scaled = correction == 1.0 ? count : (long) Math.ceil(count * correction);
        return (int) Math.min(scaled + overhead, Integer.MAX_VALUE);
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
        // A correction above 1 raises the count, so it raises the bound too, or the bound could fall below it.
        Counting counting = resolveEncoding(request.getModel());
        return corrected(total, Math.max(1.0, counting.correction()), fixedOverhead(request, counting));
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
    private static int countTokens(ToIntFunction<String> counter, String text) {
        return text == null || text.isEmpty() ? 0 : counter.applyAsInt(text);
    }

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return DEFAULT_ENCODING.countTokensOrdinary(text);
    }

    private Counting resolveEncoding(String model) {
        if (model == null) {
            return Counting.DEFAULT;
        }
        Counting cached = encodingCache.get(model);
        if (cached != null) {
            return cached;
        }
        Counting resolved = lookup(model);
        // Bounded on purpose: the key comes from the request body, so a client sending endlessly
        // varied model names would otherwise grow this map for the life of the process.
        if (encodingCache.size() < MAX_CACHED_MODELS) {
            encodingCache.putIfAbsent(model, resolved);
        }
        return resolved;
    }

    /**
     * jtokkit's own model table first, for an exact name it knows; otherwise the resolver, by the model's family. A
     * resolver that fails counts the model as an unknown family: cl100k, no correction, no tool overhead.
     */
    private Counting lookup(String model) {
        TokenizerResolver.Resolution resolution;
        try {
            resolution = resolver.resolve(model);
        } catch (RuntimeException e) {
            return Counting.DEFAULT;
        }
        double correction = resolution == null ? 1.0 : resolution.correction();
        int overhead = resolution == null ? 0 : Math.max(0, resolution.toolOverhead());
        int requestOverhead = resolution == null ? 0 : Math.max(0, resolution.requestOverhead());
        try {
            ModelType modelType = ModelType.fromName(model).orElse(null);
            if (modelType != null) {
                // jtokkit's own table names the model's vocabulary, so the count is exact.
                return new Counting(REGISTRY.getEncodingForModel(modelType)::countTokensOrdinary, correction, overhead,
                        requestOverhead, modelType.getEncodingType().getName(), true);
            }
        } catch (RuntimeException ignored) {
            // the resolver decides
        }
        String encoding = resolution == null ? null : resolution.encoding();
        ToIntFunction<String> counter = counter(encoding);
        if (counter == DEFAULT_COUNTER && !CL100K.equals(encoding)) {
            // No vocabulary, or one that did not load: counted with the cl100k stand-in, so not exact.
            return new Counting(counter, correction, overhead, requestOverhead, CL100K, false);
        }
        return new Counting(counter, correction, overhead, requestOverhead, encoding, resolution.exact());
    }

    /**
     * The counter for a vocabulary name. Mistral's and DeepSeek's are loaded once, on first use; one that did not load
     * counts as cl100k, the stand-in those families used before.
     */
    private static ToIntFunction<String> counter(String encoding) {
        if (encoding == null) {
            return DEFAULT_COUNTER;
        }
        ToIntFunction<String> counter = switch (encoding) {
            case DefaultTokenizerResolver.O200K -> O200K_COUNTER;
            case DefaultTokenizerResolver.TEKKEN -> Vocabularies.tekken();
            case DefaultTokenizerResolver.DEEPSEEK_V3 -> Vocabularies.deepseek();
            default -> DEFAULT_COUNTER;
        };
        return counter == null ? DEFAULT_COUNTER : counter;
    }
}