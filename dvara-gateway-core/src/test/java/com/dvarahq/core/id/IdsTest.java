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
package com.dvarahq.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The properties an id has to have, asserted rather than assumed.
 *
 * <p>Deliberately not asserted: that an id parses back to a TSID. Ids are opaque strings
 * everywhere in this codebase, and a test that round-trips one would be the first thing to make
 * that untrue.
 */
class IdsTest {

    @Test
    void everyIdIsThirteenCharacters() {
        // The width is load-bearing twice over: it is the storage saving, and — because every id
        // is the SAME width — it is what makes lexicographic order equal chronological order.
        IntStream.range(0, 1_000).forEach(i -> assertThat(Ids.newId()).hasSize(13));
    }

    @Test
    void idsAreUniqueUnderConcurrency() {
        // One node minting flat out. A duplicate here would mean the factory is not thread-safe,
        // which is a different failure from the fleet-level node collision the class documents.
        Set<String> seen = new ConcurrentSkipListSet<>();
        int total = 40_000;
        IntStream.range(0, total).parallel().forEach(i -> seen.add(Ids.newId()));
        assertThat(seen).hasSize(total);
    }

    @Test
    void idsSortChronologicallyAsPlainStrings() throws Exception {
        // Generated across three distinct milliseconds so the timestamp bits, not the counter, are
        // doing the ordering.
        List<String> ids = new ArrayList<>();
        for (int batch = 0; batch < 3; batch++) {
            for (int i = 0; i < 50; i++) {
                ids.add(Ids.newId());
            }
            Thread.sleep(2);
        }
        assertThat(ids).isSorted();
    }

    @Test
    void statefulSetOrdinalIsUsedVerbatim() {
        // The case that makes a Kubernetes fleet collision-free with no configuration: distinct
        // replicas get distinct nodes because the platform already numbered them.
        assertThat(Ids.trailingOrdinal("dvara-gateway-0")).isZero();
        assertThat(Ids.trailingOrdinal("dvara-gateway-7")).isEqualTo(7);
        assertThat(Ids.trailingOrdinal("dvara-gateway-server-12")).isEqualTo(12);
    }

    @Test
    void aDeploymentPodNameHasNoOrdinalToUse() {
        // Deployment replicas get random suffixes, not ordinals. Recognising one as an ordinal
        // would be worse than falling through to the hash: 'abc123' is not replica 123.
        assertThat(Ids.trailingOrdinal("dvara-gateway-6c9f7d4b8b-x7k2p")).isNull();
        assertThat(Ids.trailingOrdinal("laptop")).isNull();
        assertThat(Ids.trailingOrdinal("trailing-")).isNull();
    }

    @Test
    void theNodeIsWithinRange() {
        assertThat(Ids.node()).isBetween(0, (1 << Ids.NODE_BITS) - 1);
    }

    // ---------------------------------------------------------------------------------------------
    // Node resolution, every branch. The one that matters most is an out-of-range explicit value,
    // which silently aliases two pods onto one node.
    // ---------------------------------------------------------------------------------------------

    private static final int NODES = 1 << Ids.NODE_BITS;

    @Test
    void anExplicitNodeWins() {
        assertThat(Ids.resolveNode("7", "dvara-gateway-3")).isEqualTo(7);
    }

    @Test
    void anExplicitNodeOutsideTheRangeWrapsOntoAnotherPodsNode() {
        // The collision this class exists to prevent, reached through its supported setting: a fleet
        // numbered past 1023 has pods congruent modulo 1024 sharing a node. The value is wrapped, and
        // a warning says so — silence here is what turns a configuration error into a duplicate key
        // nobody can explain.
        assertThat(Ids.resolveNode(String.valueOf(NODES), null)).isZero();
        assertThat(Ids.resolveNode(String.valueOf(NODES + 5), null)).isEqualTo(5);
        assertThat(Ids.resolveNode("-1", null)).isEqualTo(NODES - 1);
    }

    @Test
    void anUnparseableExplicitNodeFallsThroughToTheHostname() {
        // It must not stop a pod booting, and it must not be silently the same as not setting it.
        assertThat(Ids.resolveNode("not-a-number", "dvara-gateway-4")).isEqualTo(4);
    }

    @Test
    void aStatefulSetOrdinalIsTheNode() {
        assertThat(Ids.resolveNode(null, "dvara-gateway-0")).isZero();
        assertThat(Ids.resolveNode(null, "dvara-gateway-server-11")).isEqualTo(11);
    }

    @Test
    void aDeploymentPodNameHashesIntoRange() {
        int n = Ids.resolveNode(null, "dvara-gateway-6c9f7d4b8b-x7k2p");

        assertThat(n).isBetween(0, NODES - 1);
        // Deterministic: the same pod restarting keeps its node, which is what makes the hash branch
        // survivable at all.
        assertThat(Ids.resolveNode(null, "dvara-gateway-6c9f7d4b8b-x7k2p")).isEqualTo(n);
    }

    @Test
    void aNegativeHashStillLandsInRange() {
        // String.hashCode() is signed and frequently negative; a plain % would give a negative node
        // and the factory would reject it.
        for (String name : new String[]{"a", "zz", "pod-xyz", "dvara-gateway-abcdefghij-zzzzz",
                "\u00e9\u00e9\u00e9"}) {
            assertThat(Ids.resolveNode(null, name)).isBetween(0, NODES - 1);
        }
    }

    @Test
    void withNothingToGoOnItIsRandomButInRange() {
        for (int i = 0; i < 50; i++) {
            assertThat(Ids.resolveNode(null, null)).isBetween(0, NODES - 1);
        }
    }

    @Test
    void blankIsNotAValue() {
        // An empty env var is how a chart renders an unset one, and it must behave as unset rather
        // than as an unparseable number.
        assertThat(Ids.resolveNode("", "dvara-gateway-6")).isEqualTo(6);
        assertThat(Ids.resolveNode("   ", "dvara-gateway-6")).isEqualTo(6);
    }

    // ---------------------------------------------------------------------------------------------
    // Whether a node was WRAPPED, which is the fact the warning is built on.
    //
    // Asserted on the returned value rather than by scraping a log line: the message text would be
    // the only thing under test, and this module has no logging backend on its test classpath to
    // scrape with. Making the decision a value is what makes it checkable at all.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anInRangeNodeIsNotAWrap() {
        Ids.Resolution r = Ids.resolve("7", null);

        assertThat(r.node()).isEqualTo(7);
        assertThat(r.source()).isEqualTo(Ids.Source.EXPLICIT);
        assertThat(r.wrapped()).isFalse();
    }

    @Test
    void anOutOfRangeNodeIsReportedAsAWrapWithWhatWasAskedFor() {
        // Both halves matter: the node actually used, and the number the operator believes they set.
        // A warning can only name the collision if it still knows the original.
        Ids.Resolution r = Ids.resolve(String.valueOf(NODES + 5), null);

        assertThat(r.node()).isEqualTo(5);
        assertThat(r.requested()).isEqualTo(NODES + 5);
        assertThat(r.wrapped()).isTrue();
    }

    @Test
    void aNegativeNodeIsAWrapToo() {
        Ids.Resolution r = Ids.resolve("-1", null);

        assertThat(r.node()).isEqualTo(NODES - 1);
        assertThat(r.wrapped()).isTrue();
    }

    @Test
    void anOutOfRangeStatefulSetOrdinalIsAWrap() {
        // A StatefulSet past the node range: the platform's own numbering, silently aliased.
        Ids.Resolution r = Ids.resolve(null, "dvara-gateway-" + (NODES + 2));

        assertThat(r.source()).isEqualTo(Ids.Source.ORDINAL);
        assertThat(r.node()).isEqualTo(2);
        assertThat(r.wrapped()).isTrue();
    }

    @Test
    void aHashedOrRandomNodeIsNeverAWrap() {
        // Nothing was asked for, so there is nothing to have been overridden — and a warning that
        // fired here would be noise on every Deployment and every laptop.
        assertThat(Ids.resolve(null, "dvara-gateway-6c9f7d4b8b-x7k2p").wrapped()).isFalse();
        assertThat(Ids.resolve(null, "dvara-gateway-6c9f7d4b8b-x7k2p").source())
                .isEqualTo(Ids.Source.HASH);
        assertThat(Ids.resolve(null, null).wrapped()).isFalse();
        assertThat(Ids.resolve(null, null).source()).isEqualTo(Ids.Source.RANDOM);
    }
}
