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

import com.dvarahq.core.model.ResponsesEvent;

/**
 * A provider that ended its own stream with an error: the status its error stands for, the provider's own
 * code, and, from a Responses API stream, the provider's event as it sent it. The event lets a caller of the same
 * API be handed the provider's failure unchanged.
 */
public class ProviderStreamFailure extends GatewayException {

    private final String providerCode;
    private final transient ResponsesEvent event;

    public ProviderStreamFailure(int status, String message, String providerCode, ResponsesEvent event) {
        super(REJECTED_REQUEST_STATUSES.contains(status) ? "PROVIDER_REJECTED_REQUEST" : "PROVIDER_ERROR",
                message, status);
        this.providerCode = providerCode;
        this.event = event;
    }

    /** The provider's own error code, such as {@code insufficient_quota}; null when it sent none. */
    public String providerCode() {
        return providerCode;
    }

    /** The provider's failure event as it sent it, or null. */
    public ResponsesEvent event() {
        return event;
    }
}
