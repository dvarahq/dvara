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
package com.dvarahq.core.batch;

/**
 * A check on each model a batch input file names.
 *
 * <p>The lines of a batch do not travel the {@code ChatFilter} pipeline, so a filter that refuses a
 * model on a direct request never sees them. A deployment that refuses models registers a check here
 * too, and the gateway asks it twice: when the file is uploaded, and again when a batch is submitted
 * with that file, since a model can be turned off in between.
 *
 * <p>This build includes no implementation. With none registered the gateway does not read the lines
 * and the batch path behaves as it always has. Several may be registered; each is asked, in order.
 *
 * <p>The gateway asks once for each distinct model in a file, so a file of many lines naming one model
 * costs one call. With a check registered, a line the gateway cannot read a model from is refused
 * before any check is asked: a line nobody can check is not sent.
 */
public interface BatchModelCheck {

    /**
     * Throws to refuse the file or the batch. The gateway then sends nothing to the provider, and
     * answers the caller with the thrown code, message and details, adding the number and
     * {@code custom_id} of the first line that names the model. Use the same code and details a
     * direct request for this model is refused with, so a caller handles both the same way.
     *
     * <p>The gateway writes no audit event for the refusal. A check that wants one writes it itself,
     * as a request filter does, so the refusal is recorded once.
     *
     * @param workspaceId the workspace uploading the file or submitting the batch
     * @param apiKeyId    the id of the key the call carries, never the key itself
     * @param model       the {@code body.model} of a line, exactly as the file names it
     * @throws com.dvarahq.core.exception.GatewayException to refuse
     */
    void check(String workspaceId, String apiKeyId, String model);
}
