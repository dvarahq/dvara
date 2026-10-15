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

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.ContextWindowResult;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The context-window check skips the count for a request whose bound is under both thresholds, so the bound must
 * never come out below the count, for any text or any model's encoding, and the check must decide exactly as it
 * does when it counts every request.
 */
class TiktokenUpperBoundTest {

    private static final String[] MODELS = {"gpt-4o", "gpt-4o-mini", "gpt-4", "gpt-3.5-turbo", "text-davinci-003",
            "claude-sonnet-4", null};
    private static final String[] PIECES = {"the ", "quarterly ", "report", " ", "  ", "\n", "\t", "12345", "3.14",
            "naïve ", "café", "日本語のテキスト", "中文", "한국어", "Ελληνικά ", "русский ", "😀", "👩‍💻", "🇮🇳",
            "é", "​", "<|endoftext|>", "{\"a\":1}", "https://example.com/a?b=c", "ﬁ",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "!!!???", "\u0000", "Ω≈ç√∫"};
    /** Lone surrogates, for the length check only: the tokenizer asserts its input is valid UTF-8. */
    private static final String[] LONE_SURROGATES = {"\ud800", "\udfff"};

    private final TiktokenEstimator estimator = new TiktokenEstimator();

    @Test
    void theBoundIsNeverBelowTheCount() {
        Random random = new Random(20261015L);
        for (int i = 0; i < 2_000; i++) {
            ChatRequest request = randomRequest(random);
            assertThat(estimator.upperBound(request))
                    .as("request %d, model %s", i, request.getModel())
                    .isGreaterThanOrEqualTo(estimator.estimateTokens(request));
        }
    }

    @Test
    void utf8LengthIsTheEncodedLengthOrMore() {
        Random random = new Random(7L);
        for (int i = 0; i < 5_000; i++) {
            String text = randomText(random, 1 + random.nextInt(40))
                    + (random.nextBoolean() ? LONE_SURROGATES[random.nextInt(2)] : "");
            long encoded = text.getBytes(StandardCharsets.UTF_8).length;
            long measured = TiktokenEstimator.utf8Length(text);
            if (hasLoneSurrogate(text)) {
                // The encoder writes a lone surrogate as one byte; the bound counts three.
                assertThat(measured).isGreaterThanOrEqualTo(encoded);
            } else {
                assertThat(measured).as("text %s", text).isEqualTo(encoded);
            }
        }
    }

    /**
     * The governor with the bound decides exactly as one that counts every request: same breach flags and the same
     * pruned request, over random requests, windows and workspace thresholds.
     */
    @Test
    void theCheckDecidesAsItDoesWhenItCountsEveryRequest() {
        Random random = new Random(11L);
        TokenEstimator countsEverything = new TokenEstimator() {
            @Override public int estimateTokens(ChatRequest request) { return estimator.estimateTokens(request); }
            @Override public int estimateTokens(String text) { return estimator.estimateTokens(text); }
            @Override public int upperBound(ChatRequest request) { return Integer.MAX_VALUE; }
        };
        for (int i = 0; i < 1_000; i++) {
            ChatRequest request = randomRequest(random);
            int window = 50 + random.nextInt(20_000);
            WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
            if (random.nextBoolean()) {
                int warning = random.nextInt(101);
                int hard = random.nextInt(101);
                String strategy = List.of("NONE", "TRUNCATE_OLDEST", "TRUNCATE_MIDDLE").get(random.nextInt(3));
                when(workspaces.findById(any())).thenReturn(Optional.of(Workspace.builder().id("w").name("w")
                        .metadata(Map.of("guardrail.context.warning-threshold-pct", String.valueOf(warning),
                                "guardrail.context.hard-threshold-pct", String.valueOf(hard),
                                "guardrail.context.pruning-strategy", strategy)).build()));
            } else {
                when(workspaces.findById(any())).thenReturn(Optional.empty());
            }
            ContextWindowResult bounded = new DefaultContextWindowGovernor(estimator, mock(AuditWriter.class), workspaces)
                    .evaluate(request, window, "w");
            ContextWindowResult counted = new DefaultContextWindowGovernor(countsEverything, mock(AuditWriter.class), workspaces)
                    .evaluate(request, window, "w");
            assertThat(bounded.warningThresholdBreached()).as("request %d", i).isEqualTo(counted.warningThresholdBreached());
            assertThat(bounded.hardThresholdBreached()).as("request %d", i).isEqualTo(counted.hardThresholdBreached());
            assertThat(bounded.prunedRequest()).as("request %d", i).isEqualTo(counted.prunedRequest());
            if (bounded.warningThresholdBreached() || bounded.hardThresholdBreached()) {
                assertThat(bounded.estimatedTokens()).isEqualTo(counted.estimatedTokens());
            }
        }
    }

    private static ChatRequest randomRequest(Random random) {
        List<MultimodalMessage> messages = new ArrayList<>();
        int count = 1 + random.nextInt(6);
        for (int m = 0; m < count; m++) {
            List<ContentBlock> blocks = new ArrayList<>();
            int parts = 1 + random.nextInt(3);
            for (int b = 0; b < parts; b++) {
                switch (random.nextInt(6)) {
                    case 0 -> blocks.add(new ContentBlock.ThinkingBlock(randomText(random, random.nextInt(30)), "sig"));
                    case 1 -> blocks.add(new ContentBlock.RedactedThinkingBlock(randomText(random, random.nextInt(10))));
                    default -> blocks.add(new ContentBlock.TextBlock(randomText(random, random.nextInt(200))));
                }
            }
            List<ToolCall> calls = random.nextInt(4) == 0
                    ? List.of(ToolCall.builder().id("c" + m).name("lookup_" + m)
                            .arguments("{\"q\":\"" + randomText(random, random.nextInt(20)).replace("\"", "") + "\"}").build())
                    : null;
            messages.add(MultimodalMessage.builder()
                    .role(List.of("system", "user", "assistant", "tool").get(random.nextInt(4)))
                    .content(blocks).toolCalls(calls).build());
        }
        List<ToolDefinition> tools = random.nextInt(3) == 0
                ? List.of(ToolDefinition.builder().name("lookup").description(randomText(random, random.nextInt(20)))
                        .parameters(Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string")))).build())
                : null;
        return ChatRequest.builder().model(MODELS[random.nextInt(MODELS.length)]).messages(messages).tools(tools).build();
    }

    private static boolean hasLoneSurrogate(String text) {
        return text.codePoints().anyMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }

    private static String randomText(Random random, int pieces) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < pieces; i++) {
            if (random.nextInt(8) == 0) {
                int codePoint = random.nextInt(0x2FFFF) + 1;
                text.appendCodePoint(Character.isSurrogate((char) codePoint) && codePoint <= 0xFFFF ? 'x' : codePoint);
            } else {
                text.append(PIECES[random.nextInt(PIECES.length)]);
            }
        }
        return text.toString();
    }
}
