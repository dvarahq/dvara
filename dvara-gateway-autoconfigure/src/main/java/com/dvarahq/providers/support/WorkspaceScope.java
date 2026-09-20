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
package com.dvarahq.providers.support;

/**
 * The workspace a provider call is being made for, carried as a value rather than looked up.
 *
 * <p>Credential selection needs to know whose call this is. It used to ask the servlet request,
 * which works right up until the request is gone — and on a streamed response it is gone, because
 * the controller hands back the emitter and the dispatch unwinds before the upstream call is made.
 * The attributes object is still there; the request behind it has been closed, and asking it
 * anything throws.
 *
 * <p>So the workspace travels with the call instead. It is known early — the API-key filter works it
 * out before anything else runs — and by the time it is needed there is nothing left to ask, so
 * carrying it is the only honest option.
 *
 * <p>A plain {@link ThreadLocal} and not a request-scoped anything, deliberately: the whole problem
 * is that request scope has an expiry and this value must outlive it. It is set around a provider
 * call and cleared straight afterwards, so it never leaks into whatever the thread does next.
 *
 * <p><b>Nothing here falls back to a shared credential.</b> An empty scope means "nobody said", not
 * "use the installation's key" — the reader decides what to do with that, and the reader is the
 * place where the difference between those two matters.
 */
public final class WorkspaceScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private WorkspaceScope() {
    }

    /**
     * The workspace this call belongs to, or {@code null} if nothing set one.
     *
     * <p>Null covers two different situations and cannot tell them apart: a genuinely
     * workspace-less caller (a scheduler, a health probe), and a request whose workspace should
     * have been carried and was not. Callers that care about the difference check whether a request
     * was involved at all.
     */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Runs {@code body} with the workspace in scope, restoring whatever was there before.
     *
     * <p>Restores rather than clears because these nest: a streaming call sets the scope, and the
     * resilience wrapper sets it again on the thread it hands the call to. Clearing on the way out
     * of the inner one would strip the outer.
     */
    public static <T> T callWith(String workspaceId, java.util.concurrent.Callable<T> body) throws Exception {
        String previous = CURRENT.get();
        set(workspaceId);
        try {
            return body.call();
        } finally {
            set(previous);
        }
    }

    /** As {@link #callWith}, for a body that returns nothing and throws nothing checked. */
    public static void runWith(String workspaceId, Runnable body) {
        String previous = CURRENT.get();
        set(workspaceId);
        try {
            body.run();
        } finally {
            set(previous);
        }
    }

    /**
     * Binds the scope until the returned handle is closed. For callers whose work is not a single
     * block — opening a stream, say, where the call returns an iterator that is read later.
     */
    public static Scope open(String workspaceId) {
        String previous = CURRENT.get();
        set(workspaceId);
        return () -> set(previous);
    }

    /** What {@link #open} hands back; closing restores the previous scope. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static void set(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(workspaceId);
        }
    }
}
