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
package com.dvarahq.core.call;

/**
 * A model call the gateway makes for itself rather than for a caller: grading an answer, say, or
 * deciding which model a request needs. It goes through the same governance a caller's request
 * does, and is billed to the workspace it was made for.
 *
 * <p>While one runs, {@link #current()} names it on the running thread, so code on the governed
 * path can tell it apart from a caller's request: to record its cost as the gateway's own, or to
 * avoid starting another internal call from inside it.
 *
 * @param kind          what the call is for, such as {@code eval} or {@code routing}; recorded with it
 * @param workspaceId   the workspace the call is made for and billed to
 * @param apiKeyId      the key id its usage and cost rows carry
 * @param parentTraceId the trace id of the request that caused it, or null when none did
 */
public record InternalCall(String kind, String workspaceId, String apiKeyId, String parentTraceId) {

    private static final ThreadLocal<InternalCall> CURRENT = new ThreadLocal<>();

    public InternalCall {
        require(kind, "kind");
        require(workspaceId, "workspaceId");
        require(apiKeyId, "apiKeyId");
    }

    /** The internal call running on this thread, or null when the thread serves a caller or nothing. */
    public static InternalCall current() {
        return CURRENT.get();
    }

    /**
     * Names this call as the running one until the returned scope is closed, restoring whatever
     * was there before.
     */
    public Scope open() {
        InternalCall previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** What {@link #open} hands back; closing it restores the previous value. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An internal call needs a " + name);
        }
    }
}
