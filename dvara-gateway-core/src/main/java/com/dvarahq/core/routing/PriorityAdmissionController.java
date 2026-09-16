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
package com.dvarahq.core.routing;

/**
 * Admits or throttles requests by workspace priority tier.
 *
 * <h2>Not implemented in this build</h2>
 *
 * <p>Nothing in this repository implements this interface; another module or the application may
 * register an implementation, and it is then used. With no implementation, no request is throttled
 * by workspace tier; every request is admitted and ordinary rate limiting still applies.
 */
public interface PriorityAdmissionController {

    PriorityAdmissionResult tryAdmit(PriorityTier tier);

    void release(PriorityTier tier);

}