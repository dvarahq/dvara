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
package com.dvarahq.policy;

import com.dvarahq.policy.dsl.ConditionDsl;
import com.dvarahq.policy.dsl.RuleDsl;
import com.dvarahq.policy.rule.PolicyRule;

import java.util.List;
import java.util.Set;

/**
 * Turns one family of DSL conditions into matchers, so condition kinds can live outside this module.
 *
 * <p>{@link ConditionDsl} is the wire format an operator writes and the Console validates, and it is
 * defined once, here. What varies between builds is which conditions can be compiled, not which can
 * be written down.</p>
 *
 * <p>A condition no compiler claims makes {@link PolicyCompiler} throw. On write, the Console's
 * {@code validateDsl} rejects the policy so it can never be saved active; on load, that one policy
 * is skipped and named, and the refresh is recorded degraded. Compiling an unknown condition to a
 * matcher that never fires would be indistinguishable from a policy that did not apply, so refusal
 * is deliberately loud.</p>
 */
public interface ConditionCompiler {

    /**
     * The condition kinds this compiler claims, named as they appear in the DSL
     * ({@code data_residency}, {@code time_of_day}, …). Reported when a policy uses a kind no
     * compiler claims.
     */
    Set<String> supportedConditions();

    /**
     * Matchers for whichever of its conditions are present, or an empty list. The rule is passed for
     * its id, which is what makes a compilation error name the policy an author has to fix.
     */
    List<PolicyRule> compile(ConditionDsl conditions, RuleDsl rule);

    /**
     * Whether a {@code expression:} CEL string is this compiler's to handle. Separate from
     * {@link #compile} because CEL is not a condition kind — it is an alternative to the whole
     * {@code conditions:} block.
     */
    default boolean supportsExpressions() {
        return false;
    }

    /** Compile a CEL expression, only called when {@link #supportsExpressions()} is true. */
    default PolicyRule compileExpression(RuleDsl rule) {
        throw new UnsupportedOperationException("This compiler does not handle CEL expressions");
    }
}
