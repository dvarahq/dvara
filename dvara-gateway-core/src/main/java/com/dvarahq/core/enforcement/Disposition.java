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
package com.dvarahq.core.enforcement;

/** What happened to the response as a whole. */
public enum Disposition {
    /** Nothing was found, or nothing found required a change. */
    ALLOWED,
    /** Text was changed. Irreversibly — the response path never tokenizes. */
    TRANSFORMED,
    /** The response must not be delivered. A value, not an exception: a guard has to be able to
     *  queue its refusal before recording anything, which a throw does not allow. */
    REFUSED
}
