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
package com.dvarahq.core.pii;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.List;

/**
 * Main contract for PII enforcement on chat requests and responses.
 *
 * <p>Called by controllers before forwarding to providers (request) and
 * before returning to clients (response). Behaviour depends on per-workspace
 * configuration: BLOCK, REDACT, TOKENIZE, or LOG. REDACT is irreversible removal and TOKENIZE is
 * reversible substitution.</p>
 *
 * <p>The policy module supplies the working scanner and enforcer. Reversible tokenization needs a
 * {@link PiiTokenizationService}, which another module or the application may register; its absence
 * is surfaced when a workspace requests it.</p>
 */
public interface PiiEnforcer {

    ChatRequest enforceRequest(ChatRequest request, String workspaceId);

    ChatResponse enforceResponse(ChatResponse response, String workspaceId);

    ChatRequest stripForCache(ChatRequest request, String workspaceId);

    /**
     * The action this workspace's PII settings resolve to, for callers that must name what they did
     * in an audit event: {@link #enforceBlob} returns the enforced text and not the action, and
     * recording a {@code TOKENIZE} change as {@code REDACT} would claim the value is unrecoverable.
     *
     * <p>The default is safe to inherit only because nothing decides delivery from it: streaming
     * delivery mode comes from a {@code StreamingPosture} resolved by the gateway's own configuration,
     * so an enforcer that redacts while inheriting {@code LOG} here cannot cause content to be
     * released. This value only labels audit events.</p>
     */
    default PiiAction resolvedAction(String workspaceId) {
        return PiiAction.LOG;
    }

    /**
     * Enforces the workspace's PII policy against a raw text blob — used by the Batch API surface
     * to scan an uploaded JSONL input file <em>as a unit</em> before it is forwarded to the
     * provider. Same per-workspace action semantics as {@link #enforceRequest}: BLOCK throws a
     * {@code GatewayException}, REDACT returns the redacted content, LOG audits and returns the
     * content unchanged.
     *
     * <p>The file is scanned as one blob rather than per JSONL line. The default returns the content
     * unchanged; the gateway's enforcer overrides it with real detection and enforcement.</p>
     *
     * @return the content to forward upstream (redacted if the workspace action is REDACT)
     */
    default String enforceBlob(String content, String workspaceId) {
        return content;
    }

    /**
     * Enforces over text travelling OUT to the caller: BLOCK still refuses, everything else removes
     * the value irreversibly, and nothing is ever tokenized.
     *
     * <p>{@link #enforceBlob} applies the workspace's action as configured, which is right on the
     * way to a provider and wrong on the way back: a token handed to the caller is a token they can
     * trade back, and minting on a response writes a mapping to the store. The caller is the party
     * the value is being withheld from.</p>
     *
     * <p><b>The default is the wrong thing to inherit.</b> It delegates to {@link #enforceBlob} for
     * source compatibility only, and that can tokenize an outbound response. An implementation that
     * overrides {@code enforceBlob} must override this too.</p>
     *
     * <p><b>It has live callers, so do not read the deprecation as "unused".</b> The A2A plane's
     * per-hop filter and its task service call this, and the engine adapter calls
     * {@link #enforceResponseEdits}. They are safe only because the shipped enforcer overrides both
     * with the irreversible form.</p>
     *
     * @deprecated use {@code StreamingEnforcementEngine}, which returns edits and audits nothing.
     */
    @Deprecated(since = "1.8.0")
    default String enforceResponseBlob(String content, String workspaceId) {
        return enforceBlob(content, workspaceId);
    }

    /**
     * The same decision as {@link #enforceResponseBlob}, expressed as edits rather than a new string.
     *
     * <p>For a caller that must write the result back into the structure it came from, such as text
     * spread across many {@code parts[].text} nodes in many events. Given only a new string it would
     * have to diff, and a diff of two whole strings collapses separate changes into one region.</p>
     *
     * <p>Side effects happen once: one call here answers for the whole body, where the blob form
     * audits every call. BLOCK still throws, exactly as the blob form does. An empty list means
     * nothing to change.</p>
     *
     * @deprecated {@code StreamingEnforcementEngine} supersedes it: spans there can cross segments,
     *     findings are plural, and nothing is audited.
     */
    @Deprecated(since = "1.8.0")
    default List<PiiEdit> enforceResponseEdits(String content, String workspaceId) {
        String out = enforceResponseBlob(content, workspaceId);
        return out.equals(content)
                ? List.of()
                : List.of(new PiiEdit(0, content.length(), out));
    }

    /**
     * Restores PII tokens ({@code {{PII_TYPE_hex}}}) in a response back to their
     * original values for the workspace — the opt-in "transparent round-trip".
     *
     * <p>Off by default. When a workspace enables it, a value that was tokenised in
     * the request (TOKENIZE only; REDACT stores nothing and leaves no token
     * to restore) and echoed back by the model is restored in the
     * response the client receives. Restoration is <strong>request-scoped</strong>:
     * only tokens present in {@code request} (the redacted request sent upstream)
     * are restored, so tokens minted by a response output-leak scan stay masked.
     * <strong>Must be invoked after the response is written to any cache</strong> so
     * the cache only ever holds the tokenised form — otherwise a subsequent cache
     * hit would serve raw PII to other callers of the workspace.</p>
     *
     * <p>The default returns the response unchanged. The gateway's enforcer
     * overrides it and restores tokens only when all three hold: the workspace's action is
     * {@code TOKENIZE}, {@code pii.auto-detokenize-response} is enabled, and a tokenization service
     * is present to resolve against. The action check is not redundant — without it a {@code LOG} or
     * {@code REDACT} workspace would resolve token-shaped strings a caller supplied, restoring data
     * this request never tokenized.</p>
     */
    default ChatResponse detokenizeResponse(ChatResponse response, ChatRequest request, String workspaceId) {
        return response;
    }
}
