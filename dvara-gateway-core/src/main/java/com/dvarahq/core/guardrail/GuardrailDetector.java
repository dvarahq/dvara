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

import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.List;

/**
 * Detects guardrail violations (injection, jailbreak, content policy, etc.)
 * in text, chat requests, and chat responses.
 *
 * <p>The policy module provides the working implementation: a composite over the injection,
 * content-filter and system-prompt-leak detectors, plus whatever an {@link AdditionalGuardrailDetector}
 * contributes and whatever the optional {@link MlClassifierHook} finds. There is no no-op bean;
 * an assembly whose request path requires this contract must supply the policy implementation.</p>
 */
public interface GuardrailDetector {

    GuardrailScanResult scan(String text, String workspaceId);

    /**
     * Scan a whole response in one call, evaluating each continuation group independently.
     *
     * <p>Same contract as {@code PiiDetector.scanDocument}: the guards invoke each enabled detector at
     * most once per response, and a document can hold many groups. The default loops so existing
     * detectors are unchanged; a remote implementation overrides it to batch.</p>
     */
    default List<GuardrailScanResult> scanDocument(ResponseDocument document, String workspaceId) {
        return document.groups().stream()
                .map(group -> scan(group.text(), workspaceId))
                .toList();
    }

    List<GuardrailScanResult> scanRequest(ChatRequest request, String workspaceId);

    List<GuardrailScanResult> scanResponse(ChatResponse response, String workspaceId);
}
