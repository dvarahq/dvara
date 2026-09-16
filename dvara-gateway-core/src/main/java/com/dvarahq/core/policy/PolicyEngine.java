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
package com.dvarahq.core.policy;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;

public interface PolicyEngine {

    PolicyDecision evaluate(PolicyContext context, ChatRequest request);

    /**
     * Evaluate a CANDIDATE policy DSL — what a dry-run asks, before anything is activated.
     *
     * <p><b>The default refuses.</b> Falling back to {@link #evaluate} would report what the active
     * policies say about the request, labelled as what the candidate would do, and every caller is a
     * dry-run surface whose whole purpose is the candidate. Refusing keeps the interface source
     * compatible while making the limitation impossible to mistake for an answer; the dry-run
     * surfaces catch it and show the message.
     *
     * <p>{@code DefaultPolicyEngine} overrides it and compiles the candidate on the fly.
     */
    default PolicyDecision evaluateDsl(String dsl, PolicyContext context, ChatRequest request) {
        throw new GatewayException("UNSUPPORTED_CAPABILITY",
                "This policy engine cannot evaluate a candidate DSL, so a dry-run would report on the "
                + "ACTIVE policies rather than on the policy you are testing.");
    }

    /**
     * Compile-check a policy DSL up-front so authoring mistakes surface as a
     * clear error at create/update time instead of being silently dropped at
     * the next index rebuild. {@code DefaultPolicyEngine} overrides this to
     * compile the DSL and throw {@code GatewayException("INVALID_POLICY_DSL", ...)}
     * on a syntax or semantic error.
     *
     * <p>The interface default does nothing, for an engine that holds no DSL to compile, such as
     * an embedder's own implementation. There is no always-allow fallback engine: an assembly whose
     * request path needs an engine and has none fails at construction.
     *
     * <p>Keeping this on the interface lets an admin surface validate a policy
     * without a compile-time reference to {@code PolicyCompiler}, which this module
     * cannot name — it lives in the policy engine's own module, and this is the
     * contract that module implements.
     */
    default void validateDsl(String dsl) {
        // no-op; DefaultPolicyEngine overrides this to compile + validate.
    }

    /**
     * How many ACTIVE/SHADOW policies were skipped by the last {@link #reload()} because their DSL
     * would not compile — i.e. how many are configured but <strong>not being enforced</strong>.
     *
     * <p>Exists so the reload path can tell the difference between "reloaded" and "reloaded, and
     * some of your governance is switched off", instead of reporting success after dropping a
     * policy.
     *
     * <p>Zero for the stateless engine, which compiles per request and holds no index.
     */
    default int policyCompilationFailureCount() {
        return 0;
    }

    /**
     * Reload the active policy set from the backing store, rebuilding any
     * in-memory index. Called by the data-plane config refresher when the
     * {@code config_versions.policies} counter advances (a policy was
     * created / activated / archived / rolled back via the admin API on
     * another pod), so a data-plane pod picks up the change within one poll
     * interval — no restart. Default no-op for the stateless engine, which
     * reads through on every request and has nothing to rebuild.
     */
    default void reload() {
        // no-op; DefaultPolicyEngine overrides this to rebuild its compiled index.
    }
}