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
package com.dvarahq.core.exception;

/**
 * Thrown by {@code WorkspaceStatusFilter} when a request targets a workspace whose status is
 * {@code SUSPENDED}. The status is set in the workspace store, for a file-backed store by editing
 * the workspace's {@code status}.
 *
 * <p>Maps to <b>HTTP 403 WORKSPACE_SUSPENDED</b> via {@code GlobalExceptionHandler}. 403, not 402:
 * the workspace is administratively forbidden from the data plane regardless of payment state.
 */
public class WorkspaceSuspendedException extends GatewayException {

    public static final String CODE = "WORKSPACE_SUSPENDED";

    private final String reason;

    /**
     * The reason travels as a field on the error body, not only inside the message, so a client can
     * tell one kind of suspension from another without parsing prose. The field is absent when the
     * workspace store recorded no reason.
     */
    public WorkspaceSuspendedException(String message, String reason) {
        super(CODE, message, reason == null || reason.isBlank()
                ? java.util.Map.<String, Object>of()
                : java.util.Map.<String, Object>of("reason", reason));
        this.reason = reason;
    }

    /**
     * Reason for the suspension when known (e.g. {@code "manual"}, {@code "policy"}), from
     * {@code Workspace.metadata.suspendedReason}; {@code null} if the metadata carried none.
     *
     * <p>The value is whatever the workspace store recorded. Nothing here interprets it: it is
     * carried through to the error body and the audit payload verbatim, so an operator chooses
     * the vocabulary and this build does not constrain it.
     */
    public String getReason() { return reason; }
}