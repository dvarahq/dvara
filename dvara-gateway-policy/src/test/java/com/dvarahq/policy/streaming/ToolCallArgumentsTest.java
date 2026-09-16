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
import com.dvarahq.core.enforcement.SegmentPosition;
import com.dvarahq.core.enforcement.TextEdit;
import com.dvarahq.core.enforcement.TextSpan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** How a call's JSON arguments are laid out for the engine and rendered back. */
class ToolCallArgumentsTest {

    @Test
    void everyStringAndNumberIsItsOwnGroupNumberedInOrder() {
        var p = ToolCallArguments.project(2,
                "{\"to\": \"alice@example.com\", \"n\": 42, \"ok\": true, \"none\": null, "
                        + "\"items\": [\"a\", 7], \"a/b~\": \"slash\", \"empty\": \"\"}");

        assertThat(p.opaque()).isFalse();
        assertThat(p.groups).extracting(g -> g.id().namespace())
                .containsExactly("value/0", "value/1", "value/2", "value/3", "value/4");
        assertThat(p.groups).extracting(ContinuationGroup::text)
                .containsExactly("alice@example.com", "42", "a", "7", "slash");
    }

    @Test
    void groupsAreOwnedByTheCallAndAreToolArgumentKind() {
        var p = ToolCallArguments.project(3, "{\"to\":\"x\"}");
        ContinuationGroupId id = p.groups.getFirst().id();
        assertThat(id.kind()).isEqualTo(ContinuationGroupId.Kind.TOOL_ARGUMENT);
        assertThat(id.ownerId()).isEqualTo("tool-call/3");
        assertThat(p.groups.getFirst().text()).isEqualTo("x");
    }

    @Test
    void unchangedArgumentsComeBackByteForByte() {
        String original = "{ \"to\" : \"alice\" ,\n \"n\" : 1.50 }"; // spacing Jackson would not reproduce
        var p = ToolCallArguments.project(0, original);
        assertThat(p.reassemble(new ResponseDocument(p.groups), List.of())).contains(original);
    }

    @Test
    void anEditedStringIsWrittenBackAsValidJson() throws Exception {
        var p = ToolCallArguments.project(0, "{\"to\":\"alice@example.com\",\"subject\":\"hi \\\"there\\\"\"}");
        Optional<String> out = p.reassemble(edited(p, "alice@example.com", "[REDACTED_EMAIL]"), List.of());

        assertThat(out).isPresent();
        var tree = new ObjectMapper().readTree(out.get());
        assertThat(tree.get("to").textValue()).isEqualTo("[REDACTED_EMAIL]");
        assertThat(tree.get("subject").textValue()).isEqualTo("hi \"there\"");
    }

    @Test
    void anEditedNumberBecomesTheQuotedPlaceholder() throws Exception {
        var p = ToolCallArguments.project(0, "{\"card\":4111111111111111,\"qty\":2}");
        Optional<String> out = p.reassemble(edited(p, "4111111111111111", "[REDACTED_CREDIT_CARD]"),
                List.of(edit(p, "4111111111111111", 0, 16, "[REDACTED_CREDIT_CARD]")));

        assertThat(out).contains("{\"card\":\"[REDACTED_CREDIT_CARD]\",\"qty\":2}");
        var tree = new ObjectMapper().readTree(out.get());
        assertThat(tree.get("card").isTextual()).isTrue();
    }

    @Test
    void aPartialMatchOnANumberStillReplacesTheWholeNumber() {
        var p = ToolCallArguments.project(0, "{\"n\":123456,\"m\":9}");
        // The detector matched "3456" only; the edited text would be 12[REDACTED_CUSTOM].
        Optional<String> out = p.reassemble(edited(p, "123456", "12[REDACTED_CUSTOM]"),
                List.of(edit(p, "123456", 2, 6, "[REDACTED_CUSTOM]")));
        assertThat(out).contains("{\"n\":\"[REDACTED_CUSTOM]\",\"m\":9}");
    }

    @Test
    void aChangedNumberWithNoReportedEditCannotBeRendered() {
        var p = ToolCallArguments.project(0, "{\"n\":123456}");
        assertThat(p.reassemble(edited(p, "123456", "12[REDACTED_CUSTOM]"), List.of())).isEmpty();
    }

    @Test
    void untouchedNumbersKeepEveryDigitTheyArrivedWith() {
        String original = "{\"to\":\"alice@example.com\",\"ratio\":0.12345678901234567890123456789,\"big\":1e400,\"neg\":-0.0}";
        var p = ToolCallArguments.project(0, original);
        Optional<String> out = p.reassemble(edited(p, "alice@example.com", "[REDACTED_EMAIL]"), List.of());
        assertThat(out).contains("{\"to\":\"[REDACTED_EMAIL]\",\"ratio\":0.12345678901234567890123456789,\"big\":1e400,\"neg\":-0.0}");
    }

    @Test
    void whitespaceAndEscapesBeforeTheEditedValueDoNotShiftTheSplice() throws Exception {
        String original = "{ \"greeting\" : \"caf\\u00e9 \\\"quoted\\\" \\\\ end\" ,\n  \"to\" : \"alice@example.com\" , \"n\" : [ 1, \"x\" ] }";
        var p = ToolCallArguments.project(0, original);
        assertThat(p.groups.get(0).text()).isEqualTo("caf\u00e9 \"quoted\" \\ end");
        Optional<String> out = p.reassemble(edited(p, "alice@example.com", "[REDACTED_EMAIL]"), List.of());
        assertThat(out).contains("{ \"greeting\" : \"caf\\u00e9 \\\"quoted\\\" \\\\ end\" ,\n  \"to\" : \"[REDACTED_EMAIL]\" , \"n\" : [ 1, \"x\" ] }");
        new ObjectMapper().readTree(out.get());
    }

    @Test
    void anEditedValueNeedingEscapesIsQuotedCorrectly() throws Exception {
        var p = ToolCallArguments.project(0, "{\"to\":\"alice@example.com\"}");
        Optional<String> out = p.reassemble(edited(p, "alice@example.com", "say \"hi\"\\ [REDACTED_EMAIL]"), List.of());
        assertThat(new ObjectMapper().readTree(out.orElseThrow()).get("to").textValue())
                .isEqualTo("say \"hi\"\\ [REDACTED_EMAIL]");
    }

    @Test
    void aDuplicatedKeyMakesTheArgumentsOpaqueRatherThanHidingAValue() {
        var p = ToolCallArguments.project(1, "{\"to\":\"alice@example.com\",\"to\":\"safe\"}");
        assertThat(p.opaque()).isTrue();
        assertThat(p.groups).hasSize(1);
        assertThat(p.groups.getFirst().text()).contains("alice@example.com");
    }

    @Test
    void aRootScalarCanBeEdited() {
        var p = ToolCallArguments.project(0, "\"alice@example.com\"");
        assertThat(p.groups).hasSize(1);
        assertThat(p.reassemble(edited(p, "alice@example.com", "[REDACTED_EMAIL]"), List.of())).contains("\"[REDACTED_EMAIL]\"");
    }

    @Test
    void nestedArrayElementsAreAddressable() throws Exception {
        var p = ToolCallArguments.project(0, "{\"cc\":[\"a@b.io\",\"c@d.io\"]}");
        Optional<String> out = p.reassemble(edited(p, "c@d.io", "[REDACTED_EMAIL]"), List.of());
        var tree = new ObjectMapper().readTree(out.orElseThrow());
        assertThat(tree.get("cc").get(0).textValue()).isEqualTo("a@b.io");
        assertThat(tree.get("cc").get(1).textValue()).isEqualTo("[REDACTED_EMAIL]");
    }

    @Test
    void malformedArgumentsAreOneOpaqueGroup() {
        var p = ToolCallArguments.project(1, "{\"to\":\"alice@example.com\"");
        assertThat(p.opaque()).isTrue();
        assertThat(p.groups).hasSize(1);
        assertThat(p.groups.getFirst().id()).isEqualTo(ContinuationGroupId.toolArgument(1, ""));
        assertThat(p.groups.getFirst().text()).isEqualTo("{\"to\":\"alice@example.com\"");
    }

    @Test
    void trailingTokensMakeArgumentsOpaqueRatherThanSilentlyDropped() {
        var p = ToolCallArguments.project(1, "{\"to\":\"x\"} {\"more\":\"y\"}");
        assertThat(p.opaque()).isTrue();
    }

    @Test
    void opaqueArgumentsCannotBeRenderedEvenUnchanged() {
        var p = ToolCallArguments.project(1, "{\"to\":\"alice@example.com\"");
        assertThat(p.reassemble(edited(p, "{\"to\":\"alice@example.com\"", "{\"to\":\"[REDACTED_EMAIL]\""), List.of())).isEmpty();
        assertThat(p.reassemble(new ResponseDocument(p.groups), List.of())).isEmpty();
    }

    @Test
    void anEscapedValueIsDecodedForTheDetector() {
        var p = ToolCallArguments.project(0, "{\"to\":\"alice\\u0040example.com\"}");
        assertThat(p.opaque()).isFalse();
        assertThat(p.groups.getFirst().text()).isEqualTo("alice@example.com");
        assertThat(p.reassemble(edited(p, "alice@example.com", "[REDACTED_EMAIL]"), List.of()))
                .contains("{\"to\":\"[REDACTED_EMAIL]\"}");
    }

    @Test
    void theReaderHasLimitsOfItsOwnAndBreachingThemIsUnreadable() {
        String deep = "[".repeat(1_500) + "\"alice@example.com\"" + "]".repeat(1_500);
        assertThat(ToolCallArguments.project(0, deep).opaque()).as("deeper than Jackson's default").isTrue();
        String wide = "[".repeat(900) + "\"alice@example.com\"" + "]".repeat(900);
        assertThat(ToolCallArguments.project(0, wide).opaque()).as("within the default depth").isFalse();

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < ToolCallArguments.MAX_TOKENS; i++) {
            many.append("1,");
        }
        many.append("1]");
        assertThat(ToolCallArguments.project(0, many.toString()).opaque()).as("more tokens than allowed").isTrue();
    }

    @Test
    void aValueBudgetMakesTheRestUnreadable() {
        String five = "[1,2,3,4,5]";
        assertThat(ToolCallArguments.project(0, five, 5).opaque()).isFalse();
        assertThat(ToolCallArguments.project(0, five, 5).groups).hasSize(5);
        assertThat(ToolCallArguments.project(0, five, 4).opaque()).as("one value over the budget").isTrue();
        assertThat(ToolCallArguments.project(0, "[true,null,\"\"]", 0).opaque())
                .as("nothing a detector could see costs nothing").isFalse();
    }

    @Test
    void aFailedReadStillReportsWhatItAllocated() {
        assertThat(ToolCallArguments.project(0, "[1,2,3,4,5]", 4).valuesConsumed).as("over budget").isEqualTo(4);
        assertThat(ToolCallArguments.project(0, "[1,2,3", 10).valuesConsumed).as("malformed").isEqualTo(3);
        assertThat(ToolCallArguments.project(0, "[1,2,3]", 10).valuesConsumed).isEqualTo(3);
        assertThat(ToolCallArguments.project(0, "", 10).valuesConsumed).isZero();
    }

    @Test
    void lengthBeyondJacksonsDefaultsIsUnreadableToo() {

        String longNumber = "{\"n\":" + "7".repeat(2_000) + ",\"to\":\"alice@example.com\"}";
        assertThat(ToolCallArguments.project(0, longNumber).opaque()).as("a 2,000-digit number").isTrue();
        String okNumber = "{\"n\":" + "7".repeat(900) + ",\"to\":\"alice@example.com\"}";
        var q = ToolCallArguments.project(0, okNumber);
        assertThat(q.opaque()).isFalse();
        assertThat(q.reassemble(edited(q, "alice@example.com", "[REDACTED_EMAIL]"), List.of()))
                .contains("{\"n\":" + "7".repeat(900) + ",\"to\":\"[REDACTED_EMAIL]\"}");

        String longName = "{\"" + "k".repeat(60_000) + "\":\"alice@example.com\"}";
        assertThat(ToolCallArguments.project(0, longName).opaque()).as("a 60,000-character name").isTrue();
    }

    @Test
    void emptyArgumentsHaveNothingToScanAndComeBackEmpty() {
        var p = ToolCallArguments.project(0, "");
        assertThat(p.groups).isEmpty();
        assertThat(p.reassemble(new ResponseDocument(List.of()), List.of())).contains("");
    }

    /** An engine edit landing on the value whose text is {@code text}, covering {@code [from, to)} of it. */
    private static TextEdit edit(ToolCallArguments.Projection p, String text, int from, int to, String replacement) {
        var value = p.values.stream().filter(v -> v.text().equals(text)).findFirst().orElseThrow();
        return new TextEdit(new TextSpan(new SegmentPosition(value.segmentId(), from),
                new SegmentPosition(value.segmentId(), to)), replacement);
    }

    /** The document after enforcement: every group as projected, except the one whose text was {@code text}. */
    private static ResponseDocument edited(ToolCallArguments.Projection p, String text, String replacement) {
        List<ContinuationGroup> groups = new ArrayList<>();
        for (ContinuationGroup g : p.groups) {
            if (g.text().equals(text)) {
                Segment s = g.segments().getFirst();
                groups.add(new ContinuationGroup(g.id(), List.of(new Segment(s.id(), replacement))));
            } else {
                groups.add(g);
            }
        }
        return new ResponseDocument(groups);
    }
}
