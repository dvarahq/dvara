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
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTokensTest {

    @Test
    void readsTheSizeOfPngJpegAndGif() {
        assertThat(ImageTokens.dimensions(encode("png", 640, 480))).containsExactly(640, 480);
        assertThat(ImageTokens.dimensions(encode("jpg", 1024, 333))).containsExactly(1024, 333);
        assertThat(ImageTokens.dimensions(encode("gif", 17, 900))).containsExactly(17, 900);
    }

    @Test
    void readsTheSizeOfWebp() {
        // VP8X: the canvas size minus one, as 24-bit little-endian numbers at bytes 24 and 27.
        byte[] vp8x = new byte[30];
        System.arraycopy("RIFF".getBytes(), 0, vp8x, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(), 0, vp8x, 8, 8);
        vp8x[24] = (byte) 0xFF; vp8x[25] = 0x03;   // 1023 + 1
        vp8x[27] = (byte) 0xDF; vp8x[28] = 0x02;   // 735 + 1
        assertThat(ImageTokens.dimensions(Base64.getEncoder().encodeToString(vp8x))).containsExactly(1024, 736);
    }

    @Test
    void unreadableDataHasNoSize() {
        assertThat(ImageTokens.dimensions("A".repeat(400))).isNull();
        assertThat(ImageTokens.dimensions("not base64 at all!")).isNull();
        assertThat(ImageTokens.dimensions("")).isNull();
    }

    @Test
    void openAiHighDetailCountsTilesAfterScaling() {
        assertThat(ImageTokens.openAiHigh(512, 512)).isEqualTo(85 + 170);
        // 1024 x 1024 scales to 768 x 768: four tiles.
        assertThat(ImageTokens.openAiHigh(1024, 1024)).isEqualTo(85 + 170 * 4);
        // 2048 x 4096 fits to 1024 x 2048, then 768 x 1536: two by three tiles.
        assertThat(ImageTokens.openAiHigh(2048, 4096)).isEqualTo(85 + 170 * 6);
        // A small image is not scaled up.
        assertThat(ImageTokens.openAiHigh(100, 100)).isEqualTo(85 + 170);
    }

    @Test
    void anthropicCountsPixelsOver750WithinItsCeiling() {
        assertThat(ImageTokens.anthropic(300, 250)).isEqualTo(100);
        assertThat(ImageTokens.anthropic(1092, 1092)).isEqualTo(1590);
        assertThat(ImageTokens.anthropic(8000, 8000)).isEqualTo(1600);
    }

    @Test
    void geminiCountsTiles() {
        assertThat(ImageTokens.gemini(384, 384)).isEqualTo(258);
        assertThat(ImageTokens.gemini(1000, 700)).isEqualTo(258 * 2);
    }

    @Test
    void theModelNamePicksTheFormula() {
        var image = new ContentBlock.ImageBlock("image/png", encode("png", 300, 250));
        assertThat(ImageTokens.estimate(image, "gpt-4o")).isEqualTo(85 + 170);
        assertThat(ImageTokens.estimate(image, "claude-sonnet-4-5")).isEqualTo(100);
        assertThat(ImageTokens.estimate(image, "gemini-2.5-pro")).isEqualTo(258);
    }

    @Test
    void anImageOfUnknownSizeCountsTheStatedWorstCaseNotZero() {
        var url = new ContentBlock.ImageBlock(ContentBlock.ImageBlock.URL_MEDIA_TYPE, "https://example.com/a.png");
        assertThat(ImageTokens.estimate(url, "gpt-4o")).isEqualTo(1445);
        assertThat(ImageTokens.estimate(url, "claude-opus-4-1")).isEqualTo(1600);
        assertThat(ImageTokens.estimate(url, "gemini-2.5-flash")).isEqualTo(1600);
        var urlLow = new ContentBlock.ImageBlock(ContentBlock.ImageBlock.URL_MEDIA_TYPE, "https://example.com/a.png", "low");
        assertThat(ImageTokens.estimate(urlLow, "gpt-4o")).isEqualTo(85);
    }

    private static String encode(String format, int width, int height) {
        try {
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
