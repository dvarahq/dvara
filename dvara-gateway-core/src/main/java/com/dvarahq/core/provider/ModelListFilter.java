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
package com.dvarahq.core.provider;

import java.util.List;

/**
 * Narrows the models {@code GET /v1/models} lists for one caller, so the list shows only models the caller
 * may use.
 *
 * <p>Every bean of this type is applied in order, each to what the one before it kept. With none registered
 * the list holds every model of every provider. A filter that throws or returns null is skipped, and the list
 * is answered without it: the list is a convenience, and the request checks still decide what may be called.
 *
 * <p>A filter only removes entries. An entry it returns that was not in its input is not listed.
 */
@FunctionalInterface
public interface ModelListFilter {

    /**
     * @param workspaceId the workspace of the caller's API key, or null when the request carries none
     * @param models      the models listed so far, in order
     * @return the models to keep, in any order
     */
    List<ModelInfo> filter(String workspaceId, List<ModelInfo> models);
}
