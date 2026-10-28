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
package com.dvarahq.server.service;

import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.core.model.SseChunk;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A provider stream on a canary route, counted once for its variant.
 *
 * <p>A stream is counted when it ends, when reading it fails, or when it is closed before it ends,
 * whichever comes first. A failure counts as an error. A stream the caller closes early is counted
 * as a call, not an error: the provider did not fail. The usage on the last chunk, when the provider
 * sends one, prices the call.</p>
 *
 * <p>Close and transport release are passed through to the provider stream, so wrapping it changes
 * nothing about how the connection is let go.</p>
 */
final class CanaryCountedStream implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

    /** Receives the outcome once: the usage seen (or null) and whether the stream failed. */
    interface Outcome {
        void record(ChatResponse.Usage usage, boolean error);
    }

    private final Iterator<SseChunk> upstream;
    private final Outcome outcome;
    private final AtomicBoolean counted = new AtomicBoolean();
    private volatile ChatResponse.Usage usage;

    CanaryCountedStream(Iterator<SseChunk> upstream, Outcome outcome) {
        this.upstream = upstream;
        this.outcome = outcome;
    }

    @Override
    public boolean hasNext() {
        boolean more;
        try {
            more = upstream.hasNext();
        } catch (RuntimeException e) {
            count(true);
            throw e;
        }
        if (!more) {
            count(false);
        }
        return more;
    }

    @Override
    public SseChunk next() {
        SseChunk chunk;
        try {
            chunk = upstream.next();
        } catch (RuntimeException e) {
            count(true);
            throw e;
        }
        if (chunk != null && chunk.getUsage() != null) {
            usage = chunk.getUsage();
        }
        return chunk;
    }

    @Override
    public void close() throws Exception {
        try {
            if (upstream instanceof AutoCloseable c) {
                c.close();
            }
        } finally {
            count(false);
        }
    }

    @Override
    public void releaseTransport() {
        if (upstream instanceof ReleasableUpstream r) {
            r.releaseTransport();
        }
    }

    private void count(boolean error) {
        if (counted.compareAndSet(false, true)) {
            outcome.record(usage, error);
        }
    }
}
