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

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.GptBytePairEncodingParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The vocabularies shipped beside the OpenAI ones, each loaded once per class loader on first use: Mistral's Tekken
 * and DeepSeek's V3 tokenizer. A table is several MB in memory, so it is held here, shared by every estimator,
 * rather than once per Spring context.
 *
 * <p>The files are built from each vendor's published tokenizer by {@code scripts/vocab/build_vocabularies.py}; where
 * they come from and under which licence is in {@code vocab/SOURCES.md} beside them. A file that is missing or does
 * not load is logged once, and that family is counted with the stand-in, as it was before it had its own vocabulary.
 */
final class Vocabularies {

    private static final Logger log = LoggerFactory.getLogger(Vocabularies.class);

    static final String TEKKEN_RESOURCE = "vocab/tekken.bin.gz";
    static final String DEEPSEEK_RESOURCE = "vocab/deepseek-v3.bin.gz";

    private Vocabularies() {
    }

    /** Mistral's Tekken, through jtokkit; null when it did not load. */
    static ToIntFunction<String> tekken() {
        return Tekken.COUNTER;
    }

    /** DeepSeek's V3 tokenizer; null when it did not load. */
    static ToIntFunction<String> deepseek() {
        return DeepSeek.COUNTER;
    }

    /** For the exactness test: Tekken's ids, which are its ranks, before the special-token offset. */
    static List<Integer> tekkenIds(String text) {
        return Tekken.ENCODING == null ? null : Tekken.ENCODING.encodeOrdinary(text).boxed();
    }

    /** For the exactness test: DeepSeek's ids. */
    static List<Integer> deepseekIds(String text) {
        return DeepSeek.BPE == null ? null : DeepSeek.BPE.encode(text);
    }

    private static final class Tekken {
        static final Encoding ENCODING = load();
        static final ToIntFunction<String> COUNTER = ENCODING == null ? null : ENCODING::countTokensOrdinary;

        private static Encoding load() {
            try (DataInputStream in = open(TEKKEN_RESOURCE)) {
                expect(in, "DVTK1");
                Pattern pattern = Pattern.compile(in.readUTF(), Pattern.UNICODE_CHARACTER_CLASS);
                int n = in.readInt();
                Map<byte[], Integer> ranks = new HashMap<>(n * 2);
                for (int rank = 0; rank < n; rank++) {
                    ranks.put(token(in), rank);
                }
                EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
                registry.registerGptBytePairEncoding(new GptBytePairEncodingParams("tekken", pattern, ranks, Map.of()));
                return registry.getEncoding("tekken").orElseThrow();
            } catch (IOException | RuntimeException e) {
                log.warn("The Mistral vocabulary did not load ({}); Mistral models are counted with the stand-in.",
                        e.toString());
                return null;
            }
        }
    }

    private static final class DeepSeek {
        static final ByteLevelBpe BPE = load();
        static final ToIntFunction<String> COUNTER = BPE == null ? null : BPE::count;

        private static ByteLevelBpe load() {
            try (DataInputStream in = open(DEEPSEEK_RESOURCE)) {
                expect(in, "DVDS1");
                int patterns = in.readInt();
                List<Pattern> splits = new ArrayList<>(patterns);
                for (int i = 0; i < patterns; i++) {
                    splits.add(Pattern.compile(in.readUTF(), Pattern.UNICODE_CHARACTER_CLASS));
                }
                byte[][] tokens = new byte[in.readInt()][];
                for (int id = 0; id < tokens.length; id++) {
                    tokens[id] = token(in);
                }
                int[][] merges = new int[in.readInt()][];
                for (int i = 0; i < merges.length; i++) {
                    merges[i] = new int[] {in.readInt(), in.readInt(), in.readInt()};
                }
                return new ByteLevelBpe(splits, tokens, merges);
            } catch (IOException | RuntimeException e) {
                log.warn("The DeepSeek vocabulary did not load ({}); DeepSeek models are counted with the stand-in.",
                        e.toString());
                return null;
            }
        }
    }

    private static DataInputStream open(String resource) throws IOException {
        InputStream raw = Vocabularies.class.getResourceAsStream(resource);
        if (raw == null) {
            throw new IOException(resource + " is not on the classpath");
        }
        return new DataInputStream(new GZIPInputStream(raw, 1 << 16));
    }

    private static void expect(DataInputStream in, String magic) throws IOException {
        byte[] found = in.readNBytes(magic.length());
        if (!magic.equals(new String(found, java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("not a " + magic + " file");
        }
    }

    private static byte[] token(DataInputStream in) throws IOException {
        byte[] token = new byte[in.readUnsignedShort()];
        in.readFully(token);
        return token;
    }
}
