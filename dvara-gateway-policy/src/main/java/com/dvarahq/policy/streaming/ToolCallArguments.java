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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.enforcement.ContinuationGroup;
import com.dvarahq.core.enforcement.ContinuationGroupId;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.enforcement.Segment;
import com.dvarahq.core.enforcement.SegmentId;
import com.dvarahq.core.enforcement.TextEdit;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A streamed tool call's arguments, assembled from fragments and laid out for the enforcement
 * engine.
 *
 * <p>The arguments are JSON, and a detector must not see them as one string: an email split across
 * two values would be invented at their junction, and a placeholder written into a number would
 * leave the caller with a call it cannot parse. So the JSON is read once and each string and each
 * number becomes its own continuation group, owned by the call and numbered in document order.</p>
 *
 * <p>After enforcement the edited values are spliced into the original text at the exact spans the
 * parser reported, each written as a JSON string. Nothing else is touched, so key order, whitespace
 * and untouched numbers survive byte-for-byte, which re-serialising a parsed tree would not
 * guarantee ({@code 1e400} would come back as a word). A number that carries a finding is replaced
 * whole by the quoted placeholder, since the digits around a removed value are not data anyone can
 * use.</p>
 *
 * <p>Arguments that do not parse (including a duplicated key, which a tree would silently collapse)
 * cannot be enforced, and the raw text is not a substitute because a JSON escape can hide an
 * address from a detector reading undecoded bytes. They form one opaque group scanned for the audit
 * record only; the guard refuses the response wherever an action could withhold, and under
 * {@code LOG} relays them and reports {@code scan_incomplete}.</p>
 *
 * <p>The reader has limits beside the held-characters bound: Jackson's defaults for nesting depth,
 * number length and name length, {@link #MAX_TOKENS} per call, and the guard's budget of argument
 * values per response. Arguments beyond them are unreadable and take the same fail-closed path,
 * because a few thousand characters of brackets could otherwise cost far more than that in
 * objects.</p>
 */
final class ToolCallArguments {

    /**
     * The reader's own limits: Jackson's defaults for depth, number length and name length, plus a
     * ceiling on the tokens in one call — the parser's early stop; the budget of values per response is
     * the guard's, and is what bounds the total objects kept. Beyond any of them the arguments are
     * unreadable and take the fail-closed path below.
     */
    static final int MAX_TOKENS = 65_536;

    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxTokenCount(MAX_TOKENS)
                    .build())
            .build();

    private ToolCallArguments() {
    }

    /** The fragments of one call, joined as they arrive. */
    static final class Assembly {
        final int index;
        String id;
        String name;
        /** The most characters ever charged to the bound for the id and the name; a regrowth pays only past it. */
        int idCharged;
        int nameCharged;
        final StringBuilder arguments = new StringBuilder();

        Assembly(int index) {
            this.index = index;
        }
    }

    /**
     * One value inside the arguments: its number in document order, the span of source text it
     * occupies — quotes included for a string — and the group that carries it.
     */
    record Value(String name, boolean numeric, String text, int start, int end,
                 ContinuationGroupId groupId, SegmentId segmentId) {
    }

    /**
     * One call's arguments as the engine sees them. {@code parsed} is false when the arguments did not
     * parse; then {@code values} is empty and {@code groups} holds the single opaque group.
     */
    static final class Projection {
        final int index;
        final String original;
        private final boolean parsed;
        final List<Value> values;
        final List<ContinuationGroup> groups;
        /**
         * How many values the read allocated, whether or not it succeeded. This is what the guard
         * charges to its budget: a read that gave up after allocating a thousand values spent them.
         */
        final int valuesConsumed;

        private Projection(int index, String original, boolean parsed, List<Value> values,
                           List<ContinuationGroup> groups, int valuesConsumed) {
            this.index = index;
            this.original = original;
            this.parsed = parsed;
            this.values = List.copyOf(values);
            this.groups = List.copyOf(groups);
            this.valuesConsumed = valuesConsumed;
        }

        boolean opaque() {
            return !parsed && !original.isEmpty();
        }

        /**
         * The arguments to deliver after enforcement.
         *
         * <p>The original when no value changed; otherwise the original with each edited value spliced
         * in as a JSON string — a number with a finding becomes the quoted placeholder(s) of the edits
         * that landed on it. Empty when the arguments cannot be enforced: they did not parse (the guard
         * refuses those before asking, so this is a backstop), or the engine changed a number without
         * reporting an edit — a broken engine is refused, not guessed at.</p>
         */
        Optional<String> reassemble(ResponseDocument enforced, List<TextEdit> edits) {
            Map<ContinuationGroupId, String> texts = new HashMap<>();
            for (ContinuationGroup g : enforced.groups()) {
                if (g.id().kind() == ContinuationGroupId.Kind.TOOL_ARGUMENT) {
                    texts.put(g.id(), g.text());
                }
            }
            if (!parsed) {
                return original.isEmpty() ? Optional.of(original) : Optional.empty();
            }
            StringBuilder out = new StringBuilder(original);
            boolean changed = false;
            // Right to left, so an earlier splice cannot move a later span.
            List<Value> byStart = new ArrayList<>(values);
            byStart.sort(Comparator.comparingInt(Value::start).reversed());
            for (Value v : byStart) {
                String after = texts.get(v.groupId());
                if (after == null || after.equals(v.text())) {
                    continue;
                }
                changed = true;
                String replacement = v.numeric() ? placeholders(v, edits) : after;
                if (replacement == null) {
                    return Optional.empty();
                }
                out.replace(v.start(), v.end(), quote(replacement));
            }
            return Optional.of(changed ? out.toString() : original);
        }

        /** The replacements the engine wrote onto this number, in order; null if it reported none. */
        private static String placeholders(Value v, List<TextEdit> edits) {
            StringBuilder sb = new StringBuilder();
            edits.stream()
                    .filter(e -> e.span().start().segment().equals(v.segmentId()))
                    .sorted(Comparator.comparingInt(e -> e.span().start().offset()))
                    .forEach(e -> sb.append(e.replacement()));
            return sb.isEmpty() ? null : sb.toString();
        }
    }

    /**
     * Lays out the assembled arguments of {@code call} for the engine, within a budget of values the
     * response has left; arguments holding more than that are unreadable.
     */
    static Projection project(Assembly call, int maxValues) {
        return project(call.index, call.arguments.toString(), maxValues);
    }

    static Projection project(int index, String arguments) {
        return project(index, arguments, Integer.MAX_VALUE);
    }

    static Projection project(int index, String arguments, int maxValues) {
        if (arguments.isEmpty()) {
            return new Projection(index, arguments, false, List.of(), List.of(), 0);
        }
        List<Value> values = new ArrayList<>();
        boolean ok = read(index, arguments, maxValues, values);
        if (!ok) {
            ContinuationGroupId id = ContinuationGroupId.toolArgument(index, "");
            return new Projection(index, arguments, false, List.of(),
                    List.of(new ContinuationGroup(id, List.of(new Segment(segmentId(index, ""), arguments)))),
                    values.size());
        }
        List<ContinuationGroup> groups = new ArrayList<>(values.size());
        for (Value v : values) {
            groups.add(new ContinuationGroup(v.groupId(), List.of(new Segment(v.segmentId(), v.text()))));
        }
        return new Projection(index, arguments, true, values, groups, values.size());
    }

    /**
     * Collects every string and number with its source span, in document order, into {@code out};
     * false when the text is not one complete JSON value — a syntax error, a duplicated key, tokens
     * after the value, or a breach of the reader's limits — in which case {@code out} holds whatever
     * was allocated before giving up, so the caller can charge it. Booleans and nulls carry nothing a detector could match. The empty string is
     * skipped too: there is nothing in it to see, and a group with no text counts as no scannable
     * text, so it invokes no detector.
     *
     * <p>Values are numbered, not named by JSON pointer. A pointer is as long as the value is deep,
     * so keeping one per value would let a deeply nested argument cost far more memory than its text
     * — the held bound counts characters, and this must not multiply them. Where a value sits is not
     * needed anyway: the splice works from its source span.</p>
     */
    private static boolean read(int index, String source, int maxValues, List<Value> out) {
        int depth = 0;
        boolean rootSeen = false;
        try (JsonParser p = JSON.createParser(source)) {
            JsonToken t = p.nextToken();
            if (t == null) {
                return false;
            }
            while (t != null) {
                if (rootSeen && depth == 0) {
                    return false; // a second value after the first
                }
                switch (t) {
                    case FIELD_NAME -> { }
                    case START_OBJECT, START_ARRAY -> {
                        depth++;
                        rootSeen = true;
                    }
                    case END_OBJECT, END_ARRAY -> depth--;
                    case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                        int start = (int) p.currentTokenLocation().getCharOffset();
                        boolean numeric = t != JsonToken.VALUE_STRING;
                        int end = numeric ? numberEnd(source, start) : stringEnd(source, start);
                        if (end < 0) {
                            return false; // the reported span does not line up with the text
                        }
                        String text = numeric ? source.substring(start, end) : p.getText();
                        if (numeric || !text.isEmpty()) {
                            if (out.size() >= maxValues) {
                                return false; // more values than the response may lay out
                            }
                            String name = "value/" + out.size();
                            out.add(new Value(name, numeric, text, start, end,
                                    ContinuationGroupId.toolArgument(index, name), segmentId(index, name)));
                        }
                        rootSeen = true;
                    }
                    default -> rootSeen = true; // true, false, null
                }
                t = p.nextToken();
            }
            return depth == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** The index just past the closing quote of the string opening at {@code start}, or -1. */
    private static int stringEnd(String s, int start) {
        if (start < 0 || start >= s.length() || s.charAt(start) != '"') {
            return -1;
        }
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i + 1;
            }
        }
        return -1;
    }

    /** The index just past the number literal opening at {@code start}, or -1. */
    private static int numberEnd(String s, int start) {
        if (start < 0 || start >= s.length()) {
            return -1;
        }
        int i = start;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return i == start ? -1 : i;
    }

    /** A JSON string literal for {@code s}, quotes included. */
    private static String quote(String s) {
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(s)) + "\"";
    }

    /**
     * Unique across the document: the text stream is {@code (0, "", 0)}, and every argument value names
     * its call and its number in the path, so no two segments can share an id.
     */
    private static SegmentId segmentId(int index, String name) {
        return new SegmentId(0, "/tool-call/" + index + "/" + name, 0);
    }
}
