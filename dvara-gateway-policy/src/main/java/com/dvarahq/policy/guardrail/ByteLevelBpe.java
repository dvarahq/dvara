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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A byte-level BPE tokenizer in the form a Hugging Face {@code tokenizer.json} describes: the text is split by a
 * sequence of patterns, each keeping both its matches and the text between them as pieces, and each piece's UTF-8
 * bytes are merged by the merge list's priority, lowest rank first and leftmost first on a tie. That is the order
 * Hugging Face's BPE applies, so the ids are the same as its tokenizer's for text with no special tokens in it.
 *
 * <p>Immutable once built, so one instance serves every thread.
 */
final class ByteLevelBpe {

    private final List<Pattern> splits;
    private final int[] byteToken = new int[256];
    /** (left << 32 | right) to the merge's rank. */
    private final Map<Long, Integer> rankOf;
    /** The token a merge of that rank produces. */
    private final int[] mergedId;

    /**
     * @param splits the split patterns, applied in order, each keeping its matches and the text between them
     * @param tokens every token's bytes, by id
     * @param merges the merges in priority order, each {left id, right id, merged id}
     */
    ByteLevelBpe(List<Pattern> splits, byte[][] tokens, int[][] merges) {
        this.splits = List.copyOf(splits);
        java.util.Arrays.fill(byteToken, -1);
        for (int id = 0; id < tokens.length; id++) {
            if (tokens[id].length == 1 && byteToken[tokens[id][0] & 0xFF] < 0) {
                byteToken[tokens[id][0] & 0xFF] = id;
            }
        }
        for (int b = 0; b < 256; b++) {
            if (byteToken[b] < 0) {
                throw new IllegalArgumentException("the vocabulary has no token for byte " + b);
            }
        }
        this.rankOf = new HashMap<>(merges.length * 2);
        this.mergedId = new int[merges.length];
        for (int rank = 0; rank < merges.length; rank++) {
            rankOf.putIfAbsent(pair(merges[rank][0], merges[rank][1]), rank);
            mergedId[rank] = merges[rank][2];
        }
    }

    /** The number of tokens {@code text} encodes to. */
    int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String piece : pieces(text)) {
            total += merge(piece.getBytes(StandardCharsets.UTF_8)).length;
        }
        return total;
    }

    /** The token ids {@code text} encodes to. */
    List<Integer> encode(String text) {
        List<Integer> ids = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return ids;
        }
        for (String piece : pieces(text)) {
            for (int id : merge(piece.getBytes(StandardCharsets.UTF_8))) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> pieces(String text) {
        List<String> pieces = List.of(text);
        for (Pattern split : splits) {
            List<String> next = new ArrayList<>();
            for (String piece : pieces) {
                isolate(split, piece, next);
            }
            pieces = next;
        }
        return pieces;
    }

    /** Each match and each stretch between matches, in order, empty stretches left out. */
    private static void isolate(Pattern split, String piece, List<String> out) {
        Matcher m = split.matcher(piece);
        int at = 0;
        while (m.find()) {
            if (m.end() == m.start()) {
                continue;
            }
            if (m.start() > at) {
                out.add(piece.substring(at, m.start()));
            }
            out.add(piece.substring(m.start(), m.end()));
            at = m.end();
        }
        if (at < piece.length()) {
            out.add(piece.substring(at));
        }
    }

    /** One piece's bytes, merged: a linked list of symbols and a queue of candidate merges, best first. */
    private int[] merge(byte[] bytes) {
        int n = bytes.length;
        int[] id = new int[n];
        int[] prev = new int[n];
        int[] next = new int[n];
        for (int i = 0; i < n; i++) {
            id[i] = byteToken[bytes[i] & 0xFF];
            prev[i] = i - 1;
            next[i] = i + 1 < n ? i + 1 : -1;
        }
        if (n > 1) {
            // {rank, position, left id, right id}; best rank first, then leftmost
            PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0])
                    : Integer.compare(a[1], b[1]));
            for (int i = 0; i + 1 < n; i++) {
                offer(queue, i, id[i], id[i + 1]);
            }
            while (!queue.isEmpty()) {
                int[] top = queue.poll();
                int left = top[1];
                int right = next[left];
                // Stale: either side has merged since this was queued.
                if (id[left] != top[2] || right < 0 || id[right] != top[3]) {
                    continue;
                }
                id[left] = mergedId[top[0]];
                id[right] = -1;
                next[left] = next[right];
                if (next[right] >= 0) {
                    prev[next[right]] = left;
                }
                if (prev[left] >= 0) {
                    offer(queue, prev[left], id[prev[left]], id[left]);
                }
                if (next[left] >= 0) {
                    offer(queue, left, id[left], id[next[left]]);
                }
            }
        }
        int count = 0;
        for (int i = 0; i >= 0 && i < n; i = next[i]) {
            count++;
        }
        int[] out = new int[count];
        int k = 0;
        for (int i = 0; i >= 0 && i < n; i = next[i]) {
            out[k++] = id[i];
        }
        return out;
    }

    private void offer(PriorityQueue<int[]> queue, int position, int left, int right) {
        Integer rank = rankOf.get(pair(left, right));
        if (rank != null) {
            queue.add(new int[] {rank, position, left, right});
        }
    }

    private static long pair(int left, int right) {
        return ((long) left << 32) | (right & 0xFFFFFFFFL);
    }
}
