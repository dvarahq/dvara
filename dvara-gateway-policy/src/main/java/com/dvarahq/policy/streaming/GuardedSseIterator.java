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

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.enforcement.AuditIntent;
import com.dvarahq.core.enforcement.ContinuationGroup;
import com.dvarahq.core.enforcement.ControlFinding;
import com.dvarahq.core.enforcement.EnforcementResult;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.enforcement.StreamingEnforcementEngine;
import com.dvarahq.core.enforcement.StreamingEnforcementTelemetry;
import com.dvarahq.core.enforcement.StreamingPosture;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.pii.PiiDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;

/**
 * Governs a streamed LLM response.
 *
 * <h2>Two modes, one scan</h2>
 *
 * <p>Delivery mode is resolved once, before the first chunk. If any enabled control can withhold
 * — PII {@code BLOCK}/{@code REDACT}/{@code TOKENIZE}, guardrail {@code BLOCK}, grounding
 * {@code BLOCK} — the response is <b>Deferred</b>: nothing is emitted until the stream ends. Otherwise
 * it is <b>Immediate</b>: chunks are delivered as they arrive. Either way the response is enforced
 * <b>once</b>, at termination.</p>
 *
 * <p>Under Immediate that one pass cannot change what was delivered; it produces the audit record,
 * and it is what keeps a value split across chunks detectable under {@code LOG}, so the audit trail
 * does not depend on where the provider happened to split its output.</p>
 *
 * <p>There is no per-window scanning and no overlap margin: no place in the stream is safe to cut
 * without a detector that states a maximum match span, and none does.</p>
 */
public class GuardedSseIterator implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

    private static final Logger log = LoggerFactory.getLogger(GuardedSseIterator.class);

    private final Iterator<SseChunk> upstream;
    private final AuditWriter auditWriter;
    private final String workspaceId;
    private final StreamingEnforcementConfig config;
    private final StreamingPosture posture;
    private final StreamingEnforcementEngine engine;
    private final StreamingEnforcementTelemetry telemetry;
    /** Resolved once; it must not change mid-response. */
    private final boolean deferred;
    /**
     * Why an enabled control cannot run, or null. Asked at construction, acted on before the first
     * chunk is emitted — a refusal decided after delivery would describe a response the caller
     * already has.
     */
    private final StreamingEnforcementEngine.UnavailableControl unavailableControl;

    private final Queue<SseChunk> outQueue = new ArrayDeque<>();
    private final StringBuilder accumulated = new StringBuilder();
    /** Streamed tool calls by normalised index, each assembling its arguments across chunks. */
    private final Map<Integer, ToolCallArguments.Assembly> toolCalls = new LinkedHashMap<>();
    /** Text plus every call's arguments, ids and names: the one figure the held-characters bound is judged on. */
    private int heldCharacters;
    /**
     * How many distinct tool calls one response may carry. Each call is an object held until the
     * end whatever its arguments say, so the character bound alone would not bound them: a stream of
     * empty calls costs no characters. A Deferred response past this is refused; an Immediate one keeps
     * relaying and marks the scan incomplete, since it cannot assemble what it did not keep.
     */
    static final int MAX_TOOL_CALLS = 256;
    /**
     * How many argument values (strings and numbers) one response may lay out for the engine. Each
     * value is several objects, so a bound on characters alone would let a few kilobytes of one-digit
     * numbers cost hundreds of thousands of objects. Past the cap the remaining arguments are
     * unreadable and take the unreadable-arguments path; values a failed read allocated before giving
     * up count too, or a run of unreadable calls could each allocate the whole budget.
     */
    static final int MAX_ARGUMENT_VALUES = 512;

    private String lastId;
    private String lastModel;
    /** What the upstream's terminal chunk carried: the terminal this guard emits carries it too. */
    private ChatResponse.Usage lastUsage;
    private String lastFinishReason;
    private boolean upstreamDone;
    private boolean finalized;
    private boolean terminated;
    private boolean deliveredTerminal;
    private boolean scanIncomplete;
    private boolean scanFailed;
    private boolean truncated;
    private boolean cancellationScheduled;
    private int piiEntityCount;
    private int guardrailDetectionCount;

    GuardedSseIterator(Iterator<SseChunk> upstream, PiiDetector piiDetector,
                       GuardrailDetector guardrailDetector, AuditWriter auditWriter,
                       String workspaceId, StreamingEnforcementConfig config) {
        this(upstream, piiDetector, guardrailDetector, auditWriter, workspaceId, config, null, List.of());
    }

    GuardedSseIterator(Iterator<SseChunk> upstream, PiiDetector piiDetector,
                       GuardrailDetector guardrailDetector, AuditWriter auditWriter,
                       String workspaceId, StreamingEnforcementConfig config,
                       GroundingDetector groundingDetector, List<String> sourceDocuments) {
        this(upstream, piiDetector, guardrailDetector, auditWriter, workspaceId, config,
                groundingDetector, sourceDocuments, StreamingEnforcementTelemetry.NOOP);
    }

    GuardedSseIterator(Iterator<SseChunk> upstream, PiiDetector piiDetector,
                       GuardrailDetector guardrailDetector, AuditWriter auditWriter,
                       String workspaceId, StreamingEnforcementConfig config,
                       GroundingDetector groundingDetector, List<String> sourceDocuments,
                       StreamingEnforcementTelemetry telemetry) {
        this(upstream,
                new DefaultStreamingEnforcementEngine(piiDetector, guardrailDetector, groundingDetector),
                auditWriter, workspaceId, config,
                config.toPosture(sourceDocuments == null ? List.of() : sourceDocuments), telemetry);
    }

    /** Native runtime form: both the engine and immutable posture were resolved before reading. */
    GuardedSseIterator(Iterator<SseChunk> upstream, StreamingEnforcementEngine engine,
                       AuditWriter auditWriter, String workspaceId,
                       StreamingEnforcementConfig config, StreamingPosture posture,
                       StreamingEnforcementTelemetry telemetry) {
        this.upstream = upstream;
        this.auditWriter = auditWriter;
        this.workspaceId = workspaceId;
        this.config = config;
        this.posture = java.util.Objects.requireNonNull(posture, "posture");
        this.engine = java.util.Objects.requireNonNull(engine, "engine");
        this.telemetry = telemetry == null ? StreamingEnforcementTelemetry.NOOP : telemetry;
        this.deferred = posture.withholds();
        this.unavailableControl = engine.unavailableControl(posture).orElse(null);
    }

    @Override
    public boolean hasNext() {
        fill();
        return !outQueue.isEmpty();
    }

    @Override
    public SseChunk next() {
        fill();
        if (outQueue.isEmpty()) {
            throw new NoSuchElementException();
        }
        return outQueue.poll();
    }

    private void fill() {
        // Before the first chunk, and therefore before either delivery mode has emitted anything.
        // Immediate relays as it reads, so this is the only point at which refusing still refuses.
        if (unavailableControl != null && !terminated && !finalized) {
            // The payload carries workspace_id and reason alongside the standard two keys, so an
            // audit query can find every pod refusing for a missing detector without matching on
            // prose.
            java.util.Map<String, Object> attribution = new java.util.LinkedHashMap<>();
            attribution.put("workspace_id", workspaceId);
            attribution.put("reason", unavailableControl.reason());
            refuse(unavailableControl.code(), unavailableControl.message(), attribution);
            return;
        }
        while (outQueue.isEmpty() && !terminated && !finalized) {
            boolean more;
            SseChunk chunk = null;
            try {
                more = upstream.hasNext();
                if (more) {
                    chunk = upstream.next();
                }
            } catch (RuntimeException e) {
                log.warn("Upstream failed for workspace {}: {}", workspaceId, e.getMessage());
                finalise(true);
                return;
            }
            if (!more) {
                upstreamDone = true;
                finalise(false);
                return;
            }

            lastId = chunk.getId() != null ? chunk.getId() : lastId;
            lastModel = chunk.getModel() != null ? chunk.getModel() : lastModel;
            if (chunk.getUsage() != null) lastUsage = chunk.getUsage();
            if (chunk.getFinishReason() != null) lastFinishReason = chunk.getFinishReason();

            String delta = chunk.getDelta();
            List<ToolCallDelta> fragments = chunk.getToolCalls();
            boolean hasText = delta != null && !delta.isEmpty();
            boolean hasCalls = fragments != null && !fragments.isEmpty();
            if (!hasText && !hasCalls) {
                // Metadata-only. Forwarded immediately in both modes: it carries no content, so it can
                // neither be withheld nor contribute to a decision.
                if (chunk.isDone()) {
                    upstreamDone = true;
                    finalise(false);
                    // finalise() emits this guard's own terminal, built from what the upstream's
                    // terminal carried (finish reason, usage). The upstream's chunk is queued only
                    // when no terminal has been delivered: every consumer stops at the first done
                    // chunk, so a second one, and the usage riding on it, would never be seen.
                    if (!terminated && !deliveredTerminal) {
                        outQueue.add(chunk);
                        deliveredTerminal = true;
                    }
                } else {
                    outQueue.add(chunk);
                }
                return;
            }

            if (hasText) {
                hold(delta, accumulated);
            }
            if (hasCalls && !terminated) {
                assemble(fragments);
            }
            if (terminated) {
                return; // Deferred overflow: refused without invoking any detector
            }
            if (!deferred) {
                outQueue.add(chunk);
                if (chunk.isDone()) {
                    deliveredTerminal = true;
                }
            }
            if (chunk.isDone()) {
                upstreamDone = true;
                finalise(false);
                return;
            }
        }
    }

    /**
     * Accumulates text for the single enforcement pass, bounded by the held-characters limit.
     *
     * <p>The two modes differ in what the bound can do. Deferred refuses on the spot — releasing
     * half-scanned text is exactly what holding exists to prevent, and no detector is invoked at all.
     * Immediate has already delivered, so it cannot refuse: it stops accumulating, keeps the prefix it
     * has, and reports {@code scan_incomplete}. It does <b>not</b> reset and rescan, which would
     * invoke the detector repeatedly and reintroduce boundary-dependent detection.</p>
     */
    private void hold(String delta, StringBuilder into) {
        int keep = charge(delta.length());
        if (keep > 0) {
            into.append(delta, 0, keep);
        }
    }

    /**
     * Charges {@code length} characters to the held bound and says how many of them may be kept: all
     * of them, or under Immediate only what fits. Deferred refuses on overflow (and then nothing is
     * kept, because the response is over); Immediate keeps the prefix and marks the scan incomplete.
     */
    private int charge(int length) {
        if (deferred) {
            heldCharacters += length;
            telemetry.bufferObserved("LLM", "DEFERRED", "HELD_CHARS",
                    heldCharacters, config.maxHeldCharacters());
            if (heldCharacters > config.maxHeldCharacters()) {
                telemetry.overflow("LLM", "DEFERRED", "HELD_CHARS");
                refuse("STREAM_TOO_LARGE_TO_SCAN",
                        "Streaming response exceeded " + config.maxHeldCharacters() + " characters "
                                + "while an enforcement action required the whole answer to be scanned "
                                + "as one; refused rather than emitted half-scanned");
                return 0; // the response is over; nothing more is kept
            }
            return length;
        }
        if (scanIncomplete) {
            return 0; // the prefix is fixed; the tail is delivered but not examined
        }
        int room = config.maxHeldCharacters() - heldCharacters;
        if (length <= room) {
            heldCharacters += length;
            telemetry.bufferObserved("LLM", "IMMEDIATE", "HELD_CHARS", heldCharacters,
                    config.maxHeldCharacters());
            return length;
        }
        int kept = Math.max(room, 0);
        heldCharacters += kept;
        scanIncomplete = true;
        telemetry.overflow("LLM", "IMMEDIATE", "HELD_CHARS");
        telemetry.bufferObserved("LLM", "IMMEDIATE", "HELD_CHARS",
                heldCharacters, config.maxHeldCharacters());
        log.warn("Streaming response for workspace {} passed {} characters; the tail is delivered but "
                + "will not be scanned.", workspaceId, config.maxHeldCharacters());
        return kept;
    }

    /**
     * Joins tool-call fragments onto their calls. A call's arguments are held under the same
     * bound as the text, because they are content the model produced and a detector must see them
     * whole: a value split across fragments is the boundary-leak this design exists to remove.
     */
    private void assemble(List<ToolCallDelta> fragments) {
        for (ToolCallDelta f : fragments) {
            ToolCallArguments.Assembly call = toolCalls.get(f.index());
            if (call == null) {
                if (toolCalls.size() >= MAX_TOOL_CALLS) {
                    if (deferred) {
                        telemetry.overflow("LLM", "DEFERRED", "TOOL_CALLS");
                        refuse("STREAM_TOO_LARGE_TO_SCAN",
                                "Streaming response carried more than " + MAX_TOOL_CALLS + " tool calls "
                                        + "while an enforcement action required the whole answer to be "
                                        + "scanned as one; refused rather than emitted half-scanned");
                        return;
                    }
                    if (!scanIncomplete) {
                        telemetry.overflow("LLM", "IMMEDIATE", "TOOL_CALLS");
                        log.warn("Streaming response for workspace {} carried more than {} tool calls; "
                                + "the rest are delivered but will not be scanned.", workspaceId, MAX_TOOL_CALLS);
                    }
                    scanIncomplete = true;
                    continue; // relayed as received, not assembled
                }
                call = new ToolCallArguments.Assembly(f.index());
                toolCalls.put(f.index(), call);
            }
            // The id and the name are held until the end like the arguments, so they are charged like
            // them — for the most either has ever been: a provider that repeats them on every fragment
            // pays once, and one that shrinks and regrows a name pays for the growth once.
            if (f.id() != null && !f.id().equals(call.id)) {
                if (charged(f.id().length(), call.idCharged)) {
                    call.id = f.id();
                    call.idCharged = Math.max(call.idCharged, f.id().length());
                }
                if (terminated) {
                    return;
                }
            }
            if (f.name() != null && !f.name().equals(call.name)) {
                if (charged(f.name().length(), call.nameCharged)) {
                    call.name = f.name();
                    call.nameCharged = Math.max(call.nameCharged, f.name().length());
                }
                if (terminated) {
                    return;
                }
            }
            if (f.argumentsFragment() != null && !f.argumentsFragment().isEmpty()) {
                hold(f.argumentsFragment(), call.arguments);
                if (terminated) {
                    return;
                }
            }
        }
    }

    /** Whether {@code length} characters fit the bound given {@code alreadyCharged} of them were charged before. */
    private boolean charged(int length, int alreadyCharged) {
        int extra = length - alreadyCharged;
        return extra <= 0 || charge(extra) == extra;
    }

    /** The response as the engine sees it: the text stream, then each call's argument values. */
    private ResponseDocument document(List<ToolCallArguments.Projection> calls) {
        List<ContinuationGroup> groups = new ArrayList<>();
        groups.addAll(ResponseDocument.ofText(accumulated.toString()).groups());
        for (ToolCallArguments.Projection call : calls) {
            groups.addAll(call.groups);
        }
        return new ResponseDocument(groups);
    }

    /** The single transition into Enforcing. Idempotent. */
    private void finalise(boolean upstreamFailed) {
        if (finalized || terminated) {
            return;
        }
        finalized = true;
        truncated = upstreamFailed;

        // A whole-response control cannot judge a fragment, so a truncated answer is refused rather
        // than delivered — PII and guardrail findings are per-span and would have been fine.
        if (upstreamFailed && deferred && posture.hasWholeResponseControl()) {
            terminated = true;
            finalized = false;
            refuse("STREAM_INCOMPLETE_UNGROUNDED",
                    "Upstream ended before the response was complete, and this workspace blocks "
                            + "ungrounded responses; a partial answer cannot be checked against its "
                            + "sources");
            return;
        }

        List<ToolCallArguments.Projection> calls = new ArrayList<>(toolCalls.size());
        boolean unreadable = false;
        int valuesLeft = MAX_ARGUMENT_VALUES;
        for (ToolCallArguments.Assembly call : toolCalls.values()) {
            ToolCallArguments.Projection projection = ToolCallArguments.project(call, valuesLeft);
            valuesLeft -= projection.valuesConsumed; // what the read allocated, whether or not it succeeded
            calls.add(projection);
            unreadable |= projection.opaque();
        }
        if (unreadable && deferred) {
            // Arguments that cannot be read as JSON cannot be enforced, and the raw text is not a
            // substitute — a JSON escape (the at sign as a backslash-u sequence) hides an address from
            // a detector reading the undecoded bytes. Refused without asking the detector, like an
            // overflow.
            terminated = true;
            finalized = false;
            refuse("STREAM_TOOL_ARGUMENTS_UNENFORCEABLE",
                    "A tool call's arguments could not be read as JSON, so the values in them could not "
                            + "be enforced; refused rather than delivered unexamined");
            return;
        }
        if (unreadable) {
            scanIncomplete = true; // relayed under LOG; the raw text is scanned for the record only
        }
        EnforcementResult result;
        try {
            result = engine.enforce(document(calls), posture, workspaceId);
        } catch (RuntimeException e) {
            if (deferred) {
                // Nothing can vouch for the text and nothing has left yet.
                terminated = true;
                finalized = false;
                refuse("STREAM_ERROR", "Streaming enforcement error: " + e.getMessage());
                return;
            }
            // Already delivered — a refusal would protect nothing. Recorded instead.
            scanFailed = true;
            log.warn("Enforcement failed for workspace {} after the response was delivered: {}",
                    workspaceId, e.getMessage());
            writeSummary();
            emitTerminalIfNeeded();
            return;
        }

        count(result);
        if (deferred && result.refused()) {
            terminated = true;
            finalized = false;
            String eventType = refusalEventFor(result);
            String message = "Streaming response refused by enforcement";
            queueRefusal();
            writeIntents(result);
            writeSummary();
            log.warn("Streaming response terminated for workspace {}: {} — {}",
                    workspaceId, eventType, message);
            return;
        }
        if (deferred) {
            List<ToolCallDelta> delivered = new ArrayList<>(calls.size());
            for (ToolCallArguments.Projection call : calls) {
                Optional<String> arguments = call.reassemble(result.document(), result.edits());
                if (arguments.isEmpty()) {
                    // The engine asked for a change no rendering honours — a number changed with no
                    // edit reported. Emitting the original leaks the value, emitting the edited text
                    // hands the caller a call it cannot parse. Refused, and the findings that asked for
                    // the edit are still recorded.
                    finalized = false;
                    writeIntents(result);
                    refuse("STREAM_TOOL_ARGUMENTS_UNENFORCEABLE",
                            "A value had to be removed from a tool call's arguments and the edit could "
                                    + "not be rendered as a call the caller can parse, so the response "
                                    + "was refused");
                    return;
                }
                ToolCallArguments.Assembly assembly = toolCalls.get(call.index);
                delivered.add(new ToolCallDelta(call.index, assembly.id, assembly.name, arguments.get()));
            }
            String text = result.document().assistantText();
            if (!text.isEmpty() || !delivered.isEmpty()) {
                emitTerminal(text, delivered);
            }
        }
        writeIntents(result);
        writeSummary();
        emitTerminalIfNeeded();
    }

    /** Which event type names this refusal. Consumers key on these names, so they stay stable. */
    private static String refusalEventFor(EnforcementResult result) {
        for (ControlFinding f : result.findings()) {
            if (f.outcome() == ControlFinding.Outcome.REFUSED) {
                return switch (f.control()) {
                    case PII -> "PII_BLOCKED_STREAMING";
                    case GUARDRAIL -> "GUARDRAIL_BLOCKED_STREAMING";
                    case GROUNDING -> "HALLUCINATION_DETECTED_STREAMING";
                };
            }
        }
        return "STREAM_ERROR";
    }

    private void count(EnforcementResult result) {
        for (ControlFinding f : result.findings()) {
            switch (f.control()) {
                case PII -> piiEntityCount += f.detections().size();
                case GUARDRAIL -> guardrailDetectionCount += f.detections().size();
                default -> { }
            }
        }
    }

    /**
     * Writes the engine's intents — one per semantic intent, never one per response.
     *
     * <p>Best-effort, and after the disposition has been acted on. Recording that something happened
     * must never be able to change whether it happened.</p>
     */
    private void writeIntents(EnforcementResult result) {
        for (AuditIntent intent : result.auditIntents()) {
            try {
                auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(), workspaceId,
                        intent.eventType(), intent.payload()));
            } catch (RuntimeException e) {
                log.warn("Failed to record {} for workspace {}: {}",
                        intent.eventType(), workspaceId, e.getMessage());
            }
        }
    }

    private void refuse(String eventType, String message) {
        refuse(eventType, message, Map.of());
    }

    /**
     * @param extra payload keys beyond {@code message} and {@code source}. A parameter rather than
     *              something every refusal carries: the other refusals here carry exactly two keys,
     *              and an event type keeps the shape its consumers read.
     */
    private void refuse(String eventType, String message, Map<String, Object> extra) {
        terminated = true;
        queueRefusal();

        // Queued first, deliberately: the refusal is the enforcement, the audit is the record of it.
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>(extra);
            payload.put("source", "streaming_response");
            payload.put("message", message);
            auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(), workspaceId, eventType,
                    payload));
        } catch (RuntimeException e) {
            log.warn("Failed to record {} for workspace {}; the stream is still refused: {}",
                    eventType, workspaceId, e.getMessage());
        }
        writeSummary();
        log.warn("Streaming response terminated for workspace {}: {} — {}", workspaceId, eventType, message);
    }

    private void queueRefusal() {
        outQueue.clear();
        outQueue.add(SseChunk.builder()
                .id(lastId).model(lastModel)
                .finishReason("content_filter").usage(lastUsage).done(true).build());
        deliveredTerminal = true;
    }

    /**
     * Deferred delivery: the whole enforced response on one terminal chunk — the text, and each tool
     * call as a single fragment carrying its complete arguments.
     */
    private void emitTerminal(String text, List<ToolCallDelta> calls) {
        outQueue.add(SseChunk.builder()
                .id(lastId).model(lastModel)
                .delta(text.isEmpty() ? null : text)
                .toolCalls(calls.isEmpty() ? null : List.copyOf(calls))
                .finishReason(terminalFinishReason()).usage(lastUsage).done(true).build());
        deliveredTerminal = true;
    }

    private void emitTerminalIfNeeded() {
        if (!deliveredTerminal) {
            outQueue.add(SseChunk.builder()
                    .id(lastId).model(lastModel)
                    .finishReason(terminalFinishReason()).usage(lastUsage).done(true).build());
            deliveredTerminal = true;
        }
    }

    /** The upstream's own finish reason when it sent one — length, tool_calls — else stop. */
    private String terminalFinishReason() {
        return lastFinishReason != null ? lastFinishReason : "stop";
    }

    private void writeSummary() {
        if (!posture.piiEnabled() && !posture.guardrailEnabled() && !posture.groundingActive()) {
            return; // compatibility constructors can still create a disabled guard directly
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "streaming_response");
        if (piiEntityCount > 0) {
            payload.put("pii_entity_count", piiEntityCount);
        }
        if (guardrailDetectionCount > 0) {
            payload.put("guardrail_detection_count", guardrailDetectionCount);
        }
        // Additive, and absent when false.
        if (scanIncomplete) {
            payload.put("scan_incomplete", true);
        }
        if (scanFailed) {
            payload.put("scan_failed", true);
        }
        if (truncated) {
            payload.put("truncated", true);
        }
        try {
            auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(), workspaceId,
                    "STREAMING_ENFORCEMENT_SUMMARY", payload));
        } catch (RuntimeException e) {
            log.warn("Failed to record the stream-end summary for workspace {}: {}",
                    workspaceId, e.getMessage());
        }
    }

    /**
     * Transport only, straight through to the upstream, touching none of this guard's state: the
     * caller is another thread (the container's timeout), and finalization here is single-threaded
     * by contract — it runs once, on the emit thread, when the released read returns.
     */
    @Override
    public void releaseTransport() {
        if (upstream instanceof ReleasableUpstream r) {
            r.releaseTransport();
        }
    }

    /**
     * Client cancellation.
     *
     * <p>The upstream is released <b>first</b>, so finalization never runs while a provider connection
     * is held open. Then: under Immediate the delivered text is enforced and audited — it left the
     * gateway, so the record must exist regardless of who hung up — and under Deferred everything held
     * is discarded, because nothing was delivered and there is nothing to record as disclosed.</p>
     */
    @Override
    public void close() {
        if (upstream instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
        if (finalized || terminated || cancellationScheduled) {
            return;
        }
        if (deferred) {
            accumulated.setLength(0);
            toolCalls.clear();
            finalized = true;
            truncated = true;
            writeSummary();
            return;
        }
        cancellationScheduled = true;
        scheduleCancellationFinalization();
    }

    private void scheduleCancellationFinalization() {
        StreamingCancellationFinalizer.submit("LLM", workspaceId, () -> finalise(true), telemetry);
    }
}
