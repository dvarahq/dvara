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

import io.hypersistence.tsid.TSID;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How a <b>database row's identifier</b> is minted: a TSID, 13 Crockford base-32 characters,
 * time-sorted.
 *
 * <h2>What this is not for</h2>
 *
 * <ul>
 *   <li><b>Secrets must never be TSIDs.</b> A TSID is time-sortable and mostly sequential, so
 *       anyone holding one can estimate its neighbours. Reset, invitation, verification and access
 *       tokens stay {@code UUID.randomUUID()} or stronger.
 *   <li><b>Trace ids are a wire format</b> of 32 hex characters, which is what collectors read.
 *   <li><b>Provider-compatible ids</b> ({@code resp_…}, {@code msg_…}) imitate an upstream's shape.
 * </ul>
 *
 * <p>The test is: does a database index carry it, and does anything gain from rows arriving in
 * order? If a value is read by a person, a protocol or an attacker, it is not this.</p>
 *
 * <h2>Why TSIDs for the rows</h2>
 *
 * <p>Inserts append to the right-hand edge of the index instead of scattering across it, the id
 * is 13 characters rather than 36, and {@code ORDER BY id} is chronological. Ids stay opaque
 * strings: nothing parses, validates or round-trips one, so a 13-character id and a 36-character
 * uuid can share a column.
 *
 * <h2>The node id</h2>
 *
 * <p>A TSID is 42 bits of millisecond, {@value #NODE_BITS} bits of node and a per-millisecond
 * counter, so uniqueness across a fleet rests on <b>two pods never sharing a node id</b>. Two that
 * do and mint in the same millisecond collide routinely, and a duplicate primary key aborts the
 * whole transaction. The node is therefore resolved deliberately:
 *
 * <ol>
 *   <li>{@code DVARA_ID_NODE} (or {@code -Ddvara.id.node}), an explicit integer. Always wins.
 *   <li>An <b>ordinal-suffixed hostname</b>, as a Kubernetes StatefulSet replica has
 *       ({@code dvara-gateway-0}, {@code -1}, …). The ordinal is used directly, so such a fleet is
 *       collision-free with no configuration.
 *   <li>A stable hash of the pod name, for a Deployment whose replicas have random suffixes.
 *       Distinct names usually land on distinct nodes, but nothing guarantees it; with more than
 *       a handful of replicas, set {@code DVARA_ID_NODE}.
 *   <li>A random node, for a laptop. Logged at WARN.
 * </ol>
 */
public final class Ids {

    /** 1024 nodes, leaving 4096 ids per millisecond per node. */
    static final int NODE_BITS = 10;

    private static final int NODE_COUNT = 1 << NODE_BITS;
    private static final Logger log = LoggerFactory.getLogger(Ids.class);

    private static final int NODE = resolveNode();
    private static final TSID.Factory FACTORY =
            TSID.Factory.builder().withNodeBits(NODE_BITS).withNode(NODE).build();

    private Ids() {
    }

    /**
     * Mint a new identifier.
     *
     * <p>Thread-safe: the underlying factory synchronizes, and the returned string is the only
     * thing callers ever see.
     */
    public static String newId() {
        return FACTORY.generate().toString();
    }

    /** The node id this JVM mints under. Exposed for diagnostics and tests, not for callers. */
    public static int node() {
        return NODE;
    }

    private static int resolveNode() {
        Resolution r = resolve(property("dvara.id.node", "DVARA_ID_NODE"),
                firstNonBlank(System.getenv("DVARA_POD_INSTANCE_ID"), System.getenv("HOSTNAME")));
        logResolution(r);
        return r.node();
    }

    /** Where a node id came from. */
    enum Source {
        /** {@code DVARA_ID_NODE} or {@code -Ddvara.id.node}. */
        EXPLICIT,
        /** The trailing ordinal of a StatefulSet pod name. */
        ORDINAL,
        /** A stable hash of a pod name with no ordinal. */
        HASH,
        /** Nothing to go on. */
        RANDOM
    }

    /**
     * A resolved node and how it was reached.
     *
     * @param node      the node this JVM will mint under, always in {@code 0..NODE_COUNT-1}
     * @param source    which branch decided it
     * @param requested the number asked for, when one was — equal to {@code node} unless it was
     *                  outside the range and had to be wrapped
     */
    record Resolution(int node, Source source, Integer requested) {
        /** True when the number asked for was outside the range, so this node is shared with another. */
        boolean wrapped() {
            return requested != null && requested != node;
        }
    }

    /**
     * The resolution, as a function of its two inputs and with no logging.
     *
     * <p>Separated from the environment so every branch is reachable in a test, and returning a
     * {@link Resolution} rather than an int so that {@link Resolution#wrapped()} is assertable: a
     * number outside the range is silently congruent to another pod's, which is the collision this
     * class exists to prevent.</p>
     */
    static Resolution resolve(String explicit, String podName) {
        if (explicit != null && !explicit.isBlank()) {
            try {
                int requested = Integer.parseInt(explicit.trim());
                return new Resolution(Math.floorMod(requested, NODE_COUNT), Source.EXPLICIT, requested);
            } catch (NumberFormatException e) {
                // Fall through rather than fail: an unparseable value must not stop a pod booting,
                // but it must not be silently equivalent to not setting it either — the caller logs.
                log.warn("DVARA_ID_NODE is not an integer ('{}') — deriving the TSID node from the "
                        + "hostname instead. Ids stay unique only if that derivation gives every "
                        + "pod a distinct node; set a valid integer to be certain.", explicit);
            }
        }

        if (podName != null) {
            Integer ordinal = trailingOrdinal(podName);
            if (ordinal != null) {
                return new Resolution(Math.floorMod(ordinal, NODE_COUNT), Source.ORDINAL, ordinal);
            }
            return new Resolution(Math.floorMod(podName.hashCode(), NODE_COUNT), Source.HASH, null);
        }

        return new Resolution(new SecureRandom().nextInt(NODE_COUNT), Source.RANDOM, null);
    }

    /** Kept for the tests that only care which node came out. */
    static int resolveNode(String explicit, String podName) {
        return resolve(explicit, podName).node();
    }

    private static void logResolution(Resolution r) {
        if (r.wrapped()) {
            // A fleet numbered past the range has pods whose numbers are congruent modulo
            // NODE_COUNT sharing a node, which is exactly the collision this class is built to
            // prevent, so the wrap is reported rather than applied silently.
            log.warn("Requested TSID node {} is outside 0..{} and was wrapped to {}. Any other pod "
                            + "whose number is congruent to {} modulo {} now shares this node and can "
                            + "mint colliding ids. Give each replica a number inside the range, with "
                            + "DVARA_ID_NODE if the platform's own numbering exceeds it.",
                    r.requested(), NODE_COUNT - 1, r.node(), r.requested(), NODE_COUNT);
            return;
        }
        switch (r.source()) {
            case EXPLICIT -> log.info("TSID node {} (from DVARA_ID_NODE)", r.node());
            case ORDINAL -> log.info("TSID node {} (StatefulSet ordinal)", r.node());
            case HASH -> log.info("TSID node {} (hash of the pod name — set DVARA_ID_NODE if this "
                    + "fleet has many replicas, since distinct names are not guaranteed distinct "
                    + "nodes)", r.node());
            case RANDOM -> log.warn("TSID node {} chosen at RANDOM — no DVARA_ID_NODE, "
                    + "DVARA_POD_INSTANCE_ID or HOSTNAME is set. Fine for a single process; in a "
                    + "fleet two pods can draw the same node and mint colliding ids. Set "
                    + "DVARA_ID_NODE per replica.", r.node());
        }
    }

    /** {@code dvara-gateway-7} -> 7. Null when the name does not end in {@code -<digits>}. */
    static Integer trailingOrdinal(String name) {
        int dash = name.lastIndexOf('-');
        if (dash < 0 || dash == name.length() - 1) {
            return null;
        }
        String tail = name.substring(dash + 1);
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return null; // absurdly long digit run
        }
    }

    private static String property(String systemProperty, String envVar) {
        String v = System.getProperty(systemProperty);
        return (v != null && !v.isBlank()) ? v : System.getenv(envVar);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return (b != null && !b.isBlank()) ? b.trim() : null;
    }
}