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
package com.dvarahq.core.metering;

import com.dvarahq.core.filter.FilterContext;

/**
 * Told once per governed call, after the upstream has answered and the response has been through
 * the post-dispatch pipeline, what that call cost: the upstream latency, the cost the estimator
 * puts on it, the total tokens, whether it failed, and whether the token figure is an estimate
 * rather than one the upstream reported (a stream with no usage block).
 *
 * <p>The context is the same one the filters saw, so a listener can read what they left on it.</p>
 *
 * <p><b>There is no default bean.</b> The runtime collects listeners into a list, so a build with
 * none registered is told nothing. {@link #NOOP} is for callers that construct the execution
 * service directly.</p>
 */
@FunctionalInterface
public interface CallOutcomeListener {

    void callCompleted(FilterContext ctx, long latencyMs, double costUsd, long totalTokens,
                       boolean error, boolean estimated);

    CallOutcomeListener NOOP = (ctx, latencyMs, costUsd, totalTokens, error, estimated) -> { };
}
