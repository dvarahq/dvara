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
package com.dvarahq.core.model;

/**
 * A streaming upstream whose transport can be released from another thread.
 *
 * <p>When the container times a streaming response out, the emit thread may be parked in
 * {@code hasNext()} on a provider that stopped sending, and the flag the loop checks is read only
 * after that call returns. {@code close()} cannot end the wait: the provider iterators close a
 * {@code BufferedReader}, and a thread blocked in {@code readLine()} holds the lock {@code close()}
 * needs; and the streaming guard's {@code close()} runs finalization that is single-threaded by
 * contract. This is the narrow alternative: close the response body's stream underneath — not the
 * response object, whose close drains the body first and so waits for the server — so the blocked
 * read fails, the loop exits, and finalization runs once, on the emit thread.
 *
 * <p>Contract: safe to call from any thread, at any time, more than once; best-effort; touches
 * nothing but the transport and its own idempotent close bookkeeping — never parser or enforcement
 * state. A wrapper implements it by delegating to what it wraps.
 */
public interface ReleasableUpstream {
    void releaseTransport();
}
