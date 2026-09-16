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

/**
 * One replacement to make in outbound text, at known offsets.
 *
 * <p>Enforcement over a blob returns a new string, which is enough when the text is one object and
 * not enough when it has to be written back into the parts it came from. Recovering the edits from
 * before-and-after strings means diffing, and the practical approximation — everything between the
 * longest common prefix and suffix — yields ONE region: two values with untouched text between them
 * collapse together, and that untouched text is swallowed into the first part while its own event is
 * emptied. Two values that each span a boundary are worse, because the per-part pass cannot see
 * either of them.</p>
 *
 * <p>Offsets are into the exact string handed to the enforcer, half-open, and non-overlapping in
 * ascending order. A caller applies them right-to-left so earlier offsets stay valid.</p>
 */
public record PiiEdit(int start, int end, String replacement) {

    public PiiEdit {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span " + start + ".." + end);
        }
        if (replacement == null) {
            throw new IllegalArgumentException("replacement must not be null");
        }
    }
}
