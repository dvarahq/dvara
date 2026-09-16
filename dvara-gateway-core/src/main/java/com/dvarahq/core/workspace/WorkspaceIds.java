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
package com.dvarahq.core.workspace;

import java.util.regex.Pattern;

/**
 * What a workspace id may be.
 *
 * <h2>Why there is a rule at all</h2>
 *
 * <p>A workspace id is not only a primary key. It is half of several <b>delimited composite
 * keys</b>, and in more than one place it is compared against a literal that means "no workspace":
 *
 * <ul>
 *   <li>the credential cache keys on {@code workspaceId + ":" + secretKey};</li>
 *   <li>the guardrail plugin registry keys the same way, and decides that a plugin is
 *       platform-global by testing whether its key starts with the sentinel;</li>
 *   <li>two unique indexes are declared over {@code COALESCE(workspace_id, '__platform__')}, so the
 *       sentinel is a real value in the key space rather than a convention;</li>
 *   <li>the audit chain signs {@code eventId|timestamp|workspaceId|eventType|payload|previousHash}
 *       with the fields unescaped, so a separator inside one of them moves a boundary.</li>
 * </ul>
 *
 * <p>Every other field in that last list is generated or a compile-time constant. The workspace id
 * is the one an operator can type, in the {@code workspaces:} section of {@code gateway.yaml}, the
 * bootstrap file, and the configuration import. An id of {@code __platform__} would be
 * indistinguishable from platform-global in all of the above, and an id containing {@code |} would
 * let two different audit records sign identically.
 *
 * <h2>The rule</h2>
 *
 * <p>Letters, digits, {@code .}, {@code _} and {@code -}; at most {@value #MAX_LENGTH} characters,
 * which is the width of the column; and not the sentinel, in any case.
 *
 * <p>It is a conservative set rather than a careful one. An id is an identifier, not a label — a
 * workspace has a {@code name} for anything a human reads — so nothing legitimate needs a colon, a
 * pipe, a space or a slash, and enumerating what is safe is the only version of this that stays
 * true when a new composite key is invented. Generated ids are TSIDs and pass; so does
 * {@code default}, and so does a hand-written one like {@code e5-load}.
 */
public final class WorkspaceIds {

    /** The width of the {@code workspace_id} column. */
    public static final int MAX_LENGTH = 36;

    /**
     * The value that means "no workspace" in the credential cache, the plugin registry and two
     * unique indexes. A workspace may not be called it.
     */
    public static final String PLATFORM_SENTINEL = "__platform__";

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");

    private WorkspaceIds() {}

    /**
     * Why this id is unusable, or null when it is fine.
     *
     * <p>Returns the reason rather than throwing, because the file-backed callers collect every
     * error in a document and report them together.
     */
    public static String rejectionReason(String id) {
        if (id == null || id.isBlank()) {
            return "a workspace id is required";
        }
        if (id.length() > MAX_LENGTH) {
            return "workspace id '" + id + "' is " + id.length() + " characters; the column holds "
                    + MAX_LENGTH;
        }
        if (!ALLOWED.matcher(id).matches()) {
            return "workspace id '" + id + "' contains a character that is not allowed. A workspace "
                    + "id is half of several composite keys — the credential cache, the guardrail "
                    + "plugin registry, the signed audit record — and a separator inside it moves a "
                    + "boundary in all of them. Use letters, digits, '.', '_' and '-'; anything a "
                    + "human reads belongs in the workspace's name";
        }
        if (PLATFORM_SENTINEL.equalsIgnoreCase(id)) {
            return "workspace id '" + id + "' is the value that means 'no workspace' in the "
                    + "credential cache, the guardrail plugin registry and two unique indexes. A "
                    + "workspace called this would occupy the platform-default credential slot, so "
                    + "the real default could not be created and every workspace falling through to "
                    + "it would get this one's upstream key";
        }
        return null;
    }

    /** True when the id is usable. */
    public static boolean isValid(String id) {
        return rejectionReason(id) == null;
    }

    /**
     * Refuse an unusable id.
     *
     * @throws IllegalArgumentException naming the id and the reason
     */
    public static void require(String id) {
        String reason = rejectionReason(id);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
    }
}
