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

import com.dvarahq.core.enforcement.AuditIntent;
import com.dvarahq.core.enforcement.ContinuationGroup;
import com.dvarahq.core.enforcement.ControlFinding;
import com.dvarahq.core.enforcement.Detection;
import com.dvarahq.core.enforcement.Disposition;
import com.dvarahq.core.enforcement.EditApplication;
import com.dvarahq.core.enforcement.EnforcementResult;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.enforcement.StreamingEnforcementEngine;
import com.dvarahq.core.enforcement.StreamingPosture;
import com.dvarahq.core.enforcement.TextEdit;
import com.dvarahq.core.enforcement.TextSpan;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailDetection;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiScanResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place a streamed response is judged.
 *
 * <h2>Pure</h2>
 *
 * <p>Nothing here writes an audit event, touches a repository or mutates its input. Audit intents
 * are returned and the caller writes them once, after acting on the disposition, so a caller that
 * enforces the whole body and then each part again does not audit three times.</p>
 *
 * <h2>One call per detector</h2>
 *
 * <p>Each enabled detector is invoked <b>at most once</b>. The document is passed whole and each
 * continuation group is evaluated within that one pass, so a value split across streamed deltas is
 * seen while two unrelated owners are never concatenated. There is no sentinel between groups because
 * there is no sentinel that is universally safe.</p>
 */
public class DefaultStreamingEnforcementEngine implements StreamingEnforcementEngine {

    private final PiiDetector piiDetector;
    private final GuardrailDetector guardrailDetector;
    private final GroundingDetector groundingDetector;

    public DefaultStreamingEnforcementEngine(PiiDetector piiDetector,
                                             GuardrailDetector guardrailDetector,
                                             GroundingDetector groundingDetector) {
        this.piiDetector = piiDetector;
        this.guardrailDetector = guardrailDetector;
        this.groundingDetector = groundingDetector;
    }

    @Override
    public java.util.Optional<UnavailableControl> unavailableControl(StreamingPosture posture) {
        if (posture.groundingActive() && groundingDetector == null) {
            return java.util.Optional.of(new UnavailableControl("GROUNDING_UNAVAILABLE",
                    "no_grounding_detector",
                    "Grounding is enabled for this workspace, but this build has no grounding "
                            + "detector: verifying a response against its sources needs an embedding "
                            + "model, which is not present here."));
        }
        return java.util.Optional.empty();
    }

    @Override
    public EnforcementResult enforce(ResponseDocument document, StreamingPosture posture,
                                     String workspaceId) {
        if (document == null || document.isEmpty()) {
            return EnforcementResult.allowed(document == null ? ResponseDocument.ofText("") : document);
        }

        List<ControlFinding> findings = new ArrayList<>();
        List<AuditIntent> intents = new ArrayList<>();
        List<TextEdit> edits = new ArrayList<>();
        boolean refused = false;

        // ---- PII ----------------------------------------------------------------------
        if (posture.piiEnabled()) {
            List<Detection> detections = new ArrayList<>();
            List<TextEdit> piiEdits = new ArrayList<>();
            // ONE call. The detector evaluates each group independently — looping here would turn
            // one logical scan into N invocations, which for a remote detector is N round trips.
            List<PiiScanResult> scans = piiDetector.scanDocument(document, posture.customPiiPatterns());
            for (int g = 0; g < document.groups().size(); g++) {
                ContinuationGroup group = document.groups().get(g);
                PiiScanResult scan = g < scans.size() ? scans.get(g) : null;
                if (group.text().isEmpty() || scan == null || !scan.hasPii()) {
                    continue;
                }
                for (PiiEntity entity : scan.entities()) {
                    TextSpan span = spanOf(group, entity.start(), entity.end());
                    // The detector's own confidence, not a constant: an NER layer reports a score
                    // and a pattern layer a fixed one, and the guardrail branch below already
                    // carries its detector's real number into the same field.
                    detections.add(new Detection(entity.type().name(), entity.label(), span,
                            entity.confidence()));
                    if (posture.piiAction() != PiiAction.LOG && posture.piiAction() != PiiAction.BLOCK) {
                        // REDACT and TOKENIZE are the same on the way out: the value is removed
                        // irreversibly, because a token handed to the caller is one they could trade
                        // back. The configured action is preserved on the finding; the outcome is not
                        // permitted to claim tokenization.
                        piiEdits.add(new TextEdit(span, "[REDACTED_" + entity.type().name() + "]"));
                    }
                }
            }
            if (!detections.isEmpty()) {
                boolean block = posture.piiAction() == PiiAction.BLOCK;
                refused |= block;
                edits.addAll(piiEdits);
                findings.add(new ControlFinding(ControlFinding.Control.PII,
                        posture.piiAction().name(),
                        block ? ControlFinding.Outcome.REFUSED
                              : piiEdits.isEmpty() ? ControlFinding.Outcome.OBSERVED
                                                   : ControlFinding.Outcome.TRANSFORMED,
                        detections));
                Map<String, Object> piiPayload = piiPayload(workspaceId, posture, detections);
                if (block) {
                    // This message text is part of the event's contract; keep it stable.
                    piiPayload.put("message", "PII detected in streaming response");
                }
                intents.add(new AuditIntent(block ? "PII_BLOCKED_STREAMING" : "PII_OUTPUT_LEAK",
                        piiPayload));
            }
        }

        // ---- Guardrail ----------------------------------------------------------------
        if (posture.guardrailEnabled() && guardrailDetector != null) {
            List<Detection> detections = new ArrayList<>();
            List<GuardrailDetection> raw = new ArrayList<>();
            List<GuardrailScanResult> scans = guardrailDetector.scanDocument(document, workspaceId);
            for (int g = 0; g < document.groups().size(); g++) {
                ContinuationGroup group = document.groups().get(g);
                String text = group.text();
                GuardrailScanResult scan = g < scans.size() ? scans.get(g) : null;
                if (text.isEmpty() || scan == null || !scan.hasDetections()) {
                    continue;
                }
                for (GuardrailDetection d : scan.detections()) {
                    if (d.riskScore() < posture.guardrailRiskThreshold()) {
                        continue;
                    }
                    detections.add(new Detection(d.category().name(), d.label(),
                            spanOf(group, 0, text.length()), d.riskScore()));
                    raw.add(d);
                }
            }
            if (!detections.isEmpty()) {
                boolean block = posture.guardrailAction() == GuardrailAction.BLOCK;
                refused |= block;
                findings.add(new ControlFinding(ControlFinding.Control.GUARDRAIL,
                        posture.guardrailAction().name(),
                        block ? ControlFinding.Outcome.REFUSED : ControlFinding.Outcome.OBSERVED,
                        detections));
                String eventType = switch (posture.guardrailAction()) {
                    case BLOCK -> "GUARDRAIL_BLOCKED_STREAMING";
                    case FLAG -> "GUARDRAIL_FLAGGED";
                    case LOG -> "GUARDRAIL_DETECTED";
                };
                Map<String, Object> payload =
                        controlPayload(workspaceId, posture.guardrailAction().name(), detections);
                // GUARDRAIL_FLAGGED and GUARDRAIL_DETECTED are shared with the non-streaming path,
                // so the payload carries the shape their consumers already read: detection_count,
                // categories, and the per-detection list with rule_id.
                payload.put("detection_count", raw.size());
                payload.put("categories", raw.stream()
                        .map(d -> d.category().name()).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining(", ")));
                payload.put("detections", raw.stream().map(d -> {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("category", d.category().name());
                    one.put("label", d.label());
                    one.put("risk_score", d.riskScore());
                    one.put("rule_id", d.ruleId());
                    return one;
                }).toList());
                if (block) {
                    payload.put("message", "Guardrail violation in streaming response");
                }
                intents.add(new AuditIntent(eventType, payload));
            }
        }

        // ---- Grounding ----------------------------------------------------------------
        String prose = document.assistantText();

        if (posture.groundingActive() && groundingDetector != null && !prose.isEmpty()) {
            // Judged on the prose alone: a tool call's arguments assert nothing to a reader,
            // and over flatten() an ordinary lookup_order(id: 4471) would be flagged as unsupported.
            // A response with no prose — a bare tool call — has made no claim, so the detector is
            // not asked about it; an application-supplied detector is never handed blank text.
            GroundingResult grounding = groundingDetector.check(
                    ChatRequest.builder().build(), syntheticResponse(prose),
                    posture.groundingSources());
            if (grounding != null && !grounding.grounded()) {
                boolean block = posture.groundingAction() == GuardrailAction.BLOCK;
                refused |= block;
                findings.add(new ControlFinding(ControlFinding.Control.GROUNDING,
                        posture.groundingAction().name(),
                        block ? ControlFinding.Outcome.REFUSED : ControlFinding.Outcome.OBSERVED,
                        List.of()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("workspace_id", workspaceId);
                payload.put("source", "streaming_response");
                payload.put("grounded", false);
                payload.put("confidence", grounding.confidence());
                payload.put("overall_similarity", grounding.overallSimilarity());
                payload.put("ungrounded_claim_count", grounding.ungroundedClaims().size());
                // Hashes, not the sentences: the text precedes PII redaction and the chain is append-only.
                payload.put("ungrounded_claim_hashes",
                        com.dvarahq.core.guardrail.ClaimDigests.of(grounding.ungroundedClaims()));
                if (block) {
                    payload.put("message", "Streaming response contains "
                            + grounding.ungroundedClaims().size() + " ungrounded claim(s)");
                }
                intents.add(new AuditIntent("HALLUCINATION_DETECTED_STREAMING", payload));
            }
        }

        if (refused) {
            // Nothing is transformed on a refusal — the text is not going anywhere.
            return new EnforcementResult(document, List.of(), findings, Disposition.REFUSED, intents);
        }
        if (edits.isEmpty()) {
            return new EnforcementResult(document, List.of(), findings, Disposition.ALLOWED, intents);
        }
        return new EnforcementResult(EditApplication.apply(document, edits), edits, findings,
                Disposition.TRANSFORMED, intents);
    }

    /** Group-relative offsets to a span that may cross two segments of that group. */
    private static TextSpan spanOf(ContinuationGroup group, int start, int end) {
        return new TextSpan(group.positionAt(start), group.positionAt(end));
    }

    private static Map<String, Object> piiPayload(String workspaceId, StreamingPosture posture,
                                                  List<Detection> detections) {
        Map<String, Object> payload = controlPayload(workspaceId, posture.piiAction().name(), detections);
        // The configured action and what happened to the text are separate claims.
        payload.put("configured_action", posture.piiAction().name());
        return payload;
    }

    private static Map<String, Object> controlPayload(String workspaceId, String action,
                                                      List<Detection> detections) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspace_id", workspaceId);
        payload.put("source", "streaming_response");
        payload.put("action", action);
        payload.put("entity_count", detections.size());
        // Types and counts, never values — the same rule the rest of the PII path follows.
        payload.put("entity_types", detections.stream().map(Detection::type).distinct().sorted().toList());
        return payload;
    }

    private static ChatResponse syntheticResponse(String text) {
        return ChatResponse.builder()
                .choices(List.of(ChatResponse.Choice.builder()
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock(text)))
                                .build())
                        .build()))
                .build();
    }
}
