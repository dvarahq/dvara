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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.List;

/**
 * Detects hallucinations by checking whether LLM response claims are grounded
 * in provided source documents.
 *
 * <p>This build includes no implementation, since one needs an embedding model; another module or
 * the application may register one. Without one, a request carrying grounding sources is refused
 * with {@code GROUNDING_UNAVAILABLE}. There is no no-op, because one would report every response
 * grounded, and an enabled control that passes everything looks exactly like a working one.</p>
 */
public interface GroundingDetector {

    GroundingResult check(ChatRequest request, ChatResponse response, List<String> sourceDocuments);
}