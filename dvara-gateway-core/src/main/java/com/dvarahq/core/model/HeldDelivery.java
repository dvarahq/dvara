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
 * A stream that may hold back the content it reads and deliver it later, governed, as new chunks.
 *
 * <p>A consumer that would otherwise relay the upstream's own events asks this first: while content is
 * held, the events the stream does deliver as they arrive are not the whole story, and the held content
 * comes after them in the gateway's own shape.</p>
 */
public interface HeldDelivery {

    /** Whether content is held and delivered when the stream ends, rather than as it arrives. */
    boolean holdsContent();
}
