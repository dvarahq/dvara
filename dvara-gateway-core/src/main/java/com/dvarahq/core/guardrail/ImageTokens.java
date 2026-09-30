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
package com.dvarahq.core.guardrail;

import com.dvarahq.core.model.ContentBlock;

import java.util.Base64;
import java.util.Locale;

/**
 * What an image in a request costs in input tokens, by the formula its provider publishes.
 *
 * <p>A provider does not tokenize an image's bytes. It prices the image by its pixel size, so this
 * reads the width and height from the image header and applies that provider's formula. The
 * provider is inferred from the model name, because the estimate is made before routing picks one:
 * a {@code claude} model gets Anthropic's formula, a {@code gemini} model Google's, and any other
 * model OpenAI's, which the OpenAI-compatible API this gateway serves is shaped after.</p>
 *
 * <ul>
 *   <li><b>OpenAI</b> (GPT-4o, GPT-4.1 and the tile-priced models): at {@code low} detail every image
 *       is 85 tokens. Otherwise the image is scaled to fit in 2048 x 2048, then scaled so its shorter
 *       side is at most 768, and costs 85 plus 170 for each 512 x 512 tile it covers. {@code auto}
 *       and no detail are counted as {@code high}, since the provider may choose it.</li>
 *   <li><b>Anthropic</b>: width x height / 750, after the provider's own downscale to a long edge of
 *       at most 1568 pixels and about 1600 tokens.</li>
 *   <li><b>Gemini</b>: 258 tokens when both sides are at most 384 pixels; otherwise 258 for each
 *       768 x 768 tile.</li>
 * </ul>
 *
 * <p>When the size cannot be read — an image passed as a URL, or data in a format this does not
 * parse — the estimate is a stated worst case instead of zero: 1445 tokens for OpenAI at high detail
 * (the largest image after its scaling, eight tiles), 85 at low detail, and 1600 for Anthropic and
 * Gemini. For Gemini 1600 is an approximation (about six tiles), since its tile count has no upper
 * bound. Over-counting an unreadable image is the safe side for a context-window check and an
 * input-size cap; under-counting it is how a large request gets through them.</p>
 */
public final class ImageTokens {

    /** OpenAI: every image at low detail, and the base cost of every image at high detail. */
    static final int OPENAI_BASE = 85;
    /** OpenAI: each 512 x 512 tile at high detail. */
    static final int OPENAI_PER_TILE = 170;
    /** OpenAI at high detail, size unknown: the most tiles an image can cover after scaling (2 x 4). */
    static final int OPENAI_UNKNOWN = OPENAI_BASE + OPENAI_PER_TILE * 8;
    /** Anthropic: the most one image costs, after the provider's downscale. */
    static final int ANTHROPIC_MAX = 1600;
    /** Gemini: one tile, or a whole image no larger than 384 x 384. */
    static final int GEMINI_PER_TILE = 258;
    /** Anthropic or Gemini, size unknown. */
    static final int UNKNOWN = 1600;

    /**
     * How much of the base64 payload is decoded to find the size. Every format read here puts its
     * size in the first few bytes, except JPEG, whose size follows the metadata segments; 64 KiB
     * covers the metadata of ordinary photos without decoding a large image whole on the request path.
     */
    private static final int HEADER_BYTES = 64 * 1024;

    private ImageTokens() {
    }

    /** The input tokens the image costs on the provider the model name points to. */
    public static int estimate(ContentBlock.ImageBlock image, String model) {
        if (image == null) {
            return 0;
        }
        boolean low = "low".equalsIgnoreCase(image.detail());
        int[] size = image.isUrl() ? null : dimensions(image.data());
        return switch (family(model)) {
            case ANTHROPIC -> size == null ? UNKNOWN : anthropic(size[0], size[1]);
            case GEMINI -> size == null ? UNKNOWN : gemini(size[0], size[1]);
            case OPENAI -> low ? OPENAI_BASE : size == null ? OPENAI_UNKNOWN : openAiHigh(size[0], size[1]);
        };
    }

    enum Family { OPENAI, ANTHROPIC, GEMINI }

    static Family family(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (m.contains("claude")) {
            return Family.ANTHROPIC;
        }
        if (m.contains("gemini")) {
            return Family.GEMINI;
        }
        return Family.OPENAI;
    }

    /** OpenAI at high detail. */
    static int openAiHigh(int width, int height) {
        double w = width;
        double h = height;
        double fit = Math.min(1.0, 2048.0 / Math.max(w, h));
        w *= fit;
        h *= fit;
        double shorter = Math.min(1.0, 768.0 / Math.min(w, h));
        w *= shorter;
        h *= shorter;
        int tiles = (int) (Math.ceil(w / 512.0) * Math.ceil(h / 512.0));
        return OPENAI_BASE + OPENAI_PER_TILE * tiles;
    }

    static int anthropic(int width, int height) {
        double w = width;
        double h = height;
        double fit = Math.min(1.0, 1568.0 / Math.max(w, h));
        w *= fit;
        h *= fit;
        return (int) Math.min(ANTHROPIC_MAX, Math.ceil(w * h / 750.0));
    }

    static int gemini(int width, int height) {
        if (width <= 384 && height <= 384) {
            return GEMINI_PER_TILE;
        }
        return (int) (Math.ceil(width / 768.0) * Math.ceil(height / 768.0)) * GEMINI_PER_TILE;
    }

    /**
     * The width and height read from the header of a base64 PNG, JPEG, GIF or WebP, or null when
     * the data is not one of those or the header is cut short.
     */
    static int[] dimensions(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        int chars = Math.min(base64.length(), HEADER_BYTES / 3 * 4);
        byte[] b;
        try {
            b = Base64.getMimeDecoder().decode(base64.substring(0, chars - chars % 4));
        } catch (IllegalArgumentException e) {
            return null;
        }
        int[] size = png(b);
        if (size == null) size = gif(b);
        if (size == null) size = webp(b);
        if (size == null) size = jpeg(b);
        return size == null || size[0] <= 0 || size[1] <= 0 ? null : size;
    }

    private static int[] png(byte[] b) {
        if (b.length < 24 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
            return null;
        }
        return new int[] {be32(b, 16), be32(b, 20)};
    }

    private static int[] gif(byte[] b) {
        if (b.length < 10 || b[0] != 'G' || b[1] != 'I' || b[2] != 'F') {
            return null;
        }
        return new int[] {le16(b, 6), le16(b, 8)};
    }

    private static int[] webp(byte[] b) {
        if (b.length < 30 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'E' || b[10] != 'B' || b[11] != 'P') {
            return null;
        }
        String chunk = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (chunk) {
            case "VP8 " -> new int[] {le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF};
            case "VP8L" -> {
                int b0 = b[21] & 0xFF, b1 = b[22] & 0xFF, b2 = b[23] & 0xFF, b3 = b[24] & 0xFF;
                yield new int[] {1 + (((b1 & 0x3F) << 8) | b0),
                        1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6))};
            }
            case "VP8X" -> new int[] {1 + le24(b, 24), 1 + le24(b, 27)};
            default -> null;
        };
    }

    private static int[] jpeg(byte[] b) {
        if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return null;
        }
        int i = 2;
        while (i + 3 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            // Start of frame: every SOFn except the DHT, JPG and DAC markers that share the range.
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return i + 8 < b.length ? new int[] {be16(b, i + 7), be16(b, i + 5)} : null;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return null;
            }
            i += 2 + be16(b, i + 2);
        }
        return null;
    }

    private static int be16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int be32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private static int le16(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16);
    }
}
