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

import com.dvarahq.core.policy.Policy;
import com.dvarahq.policy.dsl.ConditionDsl;
import com.dvarahq.policy.dsl.PolicyDsl;
import com.dvarahq.policy.dsl.RuleDsl;
import com.dvarahq.policy.rule.MaxTokensRule;
import com.dvarahq.policy.rule.ModelAllowlistRule;
import com.dvarahq.policy.rule.ModelDenylistRule;
import com.dvarahq.policy.rule.PolicyRule;
import com.dvarahq.policy.rule.ToolAllowlistRule;
import com.dvarahq.policy.rule.ToolDenylistRule;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PolicyCompiler {

    /** Condition kinds this class compiles itself; everything else must be claimed by a contributor. */
    private static final Set<String> BUILT_IN = Set.of("model", "max_tokens", "tools");

    private final List<ConditionCompiler> conditionCompilers;

    /**
     * Compiles only the built-in kinds. Anything else has to be contributed, and anything unclaimed
     * is refused at compile time rather than compiled into a matcher that never fires.
     */
    public PolicyCompiler() {
        this(List.of());
    }

    public PolicyCompiler(List<ConditionCompiler> conditionCompilers) {
        this.conditionCompilers = List.copyOf(conditionCompilers);
    }

    private Set<String> supportedConditionNames() {
        var all = new java.util.TreeSet<>(BUILT_IN);
        conditionCompilers.forEach(c -> all.addAll(c.supportedConditions()));
        return all;
    }

    /** The first condition present in the DSL that nothing here can compile, or null. */
    private String unclaimedConditions(ConditionDsl c) {
        Set<String> supported = supportedConditionNames();
        if (c.getDataResidency() != null && !supported.contains("data_residency")) {
            return "data_residency";
        }
        if (c.getTimeOfDay() != null && !supported.contains("time_of_day")) {
            return "time_of_day";
        }
        if (c.getBudgetUtilization() != null && !supported.contains("budget_utilization")) {
            return "budget_utilization";
        }
        if (c.getMcpServer() != null && !supported.contains("mcp_server")) {
            return "mcp_server";
        }
        if (c.getMcpTool() != null && !supported.contains("mcp_tool")) {
            return "mcp_tool";
        }
        if (c.getMcpArg() != null && !supported.contains("mcp_arg")) {
            return "mcp_arg";
        }
        return null;
    }


    /** Allowed `action:` values on a rule. Anything else is rejected at compile
     *  time so authoring typos (e.g. lowercase `warn`, made-up `shadow`) surface
     *  immediately instead of silently falling through to DENY. SHADOW is a
     *  policy-LIFECYCLE status (PolicyStatus.SHADOW), not a per-rule action. */
    private static final Set<String> ALLOWED_ACTIONS = Set.of("DENY", "WARN_AGENT");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public CompiledPolicy compile(Policy policy) {
        PolicyDsl dsl = parseDsl(policy.getDsl());
        List<CompiledRule> rules = compileRules(dsl);
        return new CompiledPolicy(policy.getId(), policy.getWorkspaceId(), policy.getStatus(), rules);
    }

    public CompiledPolicy compileDsl(String dslString) {
        PolicyDsl dsl = parseDsl(dslString);
        List<CompiledRule> rules = compileRules(dsl);
        return new CompiledPolicy(null, null, null, rules);
    }

    /**
     * Parse a policy's YAML into the DSL model.
     *
     * <p>Public so that other modules read a policy through the same parser this compiler uses; a
     * second parser would drift on exactly the malformed input where it matters most.</p>
     */
    public PolicyDsl parseDsl(String dslString) {
        try {
            return yamlMapper.readValue(dslString, PolicyDsl.class);
        } catch (UnrecognizedPropertyException e) {
            // Name the unknown key and add a hint. Jackson's own message for an unknown
            // property is a dense stack trace, and a typo on a known field (or a key that is
            // not part of the DSL, such as `conditions: { workspace: X }`) is the most common
            // authoring mistake.
            String key = e.getPropertyName();
            String hint = hintForUnknownKey(key);
            throw new PolicyCompilationException(
                    "Unknown key '" + key + "' in policy DSL"
                            + locationSuffix(e) + "."
                            + (hint == null ? "" : " " + hint),
                    e);
        } catch (JsonMappingException e) {
            throw new PolicyCompilationException(
                    "Failed to parse policy DSL" + locationSuffix(e) + ": " + e.getOriginalMessage(),
                    e);
        } catch (Exception e) {
            throw new PolicyCompilationException("Failed to parse policy DSL: " + e.getMessage(), e);
        }
    }

    /** Hand-picked hints for the most common authoring mistakes. Returning
     *  null means no hint — the unknown-key message is still emitted. */
    private static String hintForUnknownKey(String key) {
        if (key == null) return null;
        return switch (key.toLowerCase()) {
            case "workspace", "workspaces", "workspace_id", "workspaceid" ->
                    "Workspace scoping is at the policy entity level (set `workspaceId` on the policy when creating it), "
                            + "not a per-rule condition.";
            case "shadow" ->
                    "SHADOW is a policy-lifecycle status (PolicyStatus.SHADOW), not a per-rule action, "
                            + "and this build evaluates only ACTIVE policies.";
            case "warn" ->
                    "Did you mean `WARN_AGENT`? Action strings are case-insensitive but must be one of "
                            + ALLOWED_ACTIONS + ".";
            case "condition" -> "Did you mean `conditions:` (plural)?";
            case "rule" -> "Did you mean `rules:` (plural)?";
            default -> null;
        };
    }

    private static String locationSuffix(JsonMappingException e) {
        JsonLocation loc = e.getLocation();
        if (loc == null) return "";
        return " (line " + loc.getLineNr() + ", column " + loc.getColumnNr() + ")";
    }

    private List<CompiledRule> compileRules(PolicyDsl dsl) {
        if (dsl.getRules() == null) {
            return List.of();
        }
        return dsl.getRules().stream()
                .map(this::compileRule)
                .toList();
    }

    private CompiledRule compileRule(RuleDsl rule) {
        PolicyRule matcher = buildMatcher(rule);
        String action = rule.getAction() != null ? rule.getAction() : "DENY";
        String normalizedAction = action.toUpperCase();
        if (!ALLOWED_ACTIONS.contains(normalizedAction)) {
            throw new PolicyCompilationException(
                    "Rule '" + rule.getId() + "' has unknown action '" + action + "'. "
                            + "Allowed actions are " + ALLOWED_ACTIONS + " (case-insensitive). "
                            + "Note: SHADOW is a policy-lifecycle status, not a per-rule action, "
                            + "and this build evaluates only ACTIVE policies.",
                    null);
        }
        String message = "WARN_AGENT".equals(normalizedAction)
                ? rule.getWarnMessage()
                : rule.getDenyMessage();
        return new CompiledRule(
                rule.getId(),
                rule.getPriority(),
                normalizedAction,
                message,
                matcher);
    }

    private PolicyRule buildMatcher(RuleDsl rule) {
        // `expression:` and `conditions:` are mutually exclusive at the
        // rule level. A rule must specify exactly one of them; specifying
        // both is ambiguous (which matcher wins?), specifying neither is a
        // no-op rule that's almost certainly an authoring mistake.
        boolean hasExpression = rule.getExpression() != null && !rule.getExpression().isBlank();
        boolean hasConditions = rule.getConditions() != null;
        if (hasExpression && hasConditions) {
            throw new PolicyCompilationException(
                    "Rule '" + rule.getId() + "' specifies both `expression:` (CEL) and "
                            + "`conditions:` (closed-form matchers). They are mutually exclusive — "
                            + "use one or the other, not both.",
                    null);
        }
        if (!hasExpression && !hasConditions) {
            throw new PolicyCompilationException(
                    "Rule '" + rule.getId() + "' has no `expression:` or `conditions:` block — "
                            + "a rule with no matcher would either never fire or always fire, "
                            + "which is almost certainly an authoring mistake.",
                    null);
        }
        if (hasExpression) {
            return buildCelMatcher(rule);
        }

        ConditionDsl conditions = rule.getConditions();

        List<PolicyRule> matchers = new ArrayList<>();

        if (conditions.getModel() != null) {
            var model = conditions.getModel();
            if (model.getAllowlist() != null && !model.getAllowlist().isEmpty()) {
                matchers.add(new ModelAllowlistRule(new HashSet<>(model.getAllowlist())));
            }
            if (model.getDenylist() != null && !model.getDenylist().isEmpty()) {
                matchers.add(new ModelDenylistRule(new HashSet<>(model.getDenylist())));
            }
        }

        if (conditions.getMaxTokens() != null && conditions.getMaxTokens().getLimit() > 0) {
            matchers.add(new MaxTokensRule(conditions.getMaxTokens().getLimit()));
        }

        if (conditions.getTools() != null) {
            var tools = conditions.getTools();
            if (tools.getDenylist() != null && !tools.getDenylist().isEmpty()) {
                matchers.add(new ToolDenylistRule(new HashSet<>(tools.getDenylist())));
            }
            if (tools.getAllowlist() != null && !tools.getAllowlist().isEmpty()) {
                matchers.add(new ToolAllowlistRule(new HashSet<>(tools.getAllowlist())));
            }
        }

        // Everything above is built in: model allow/deny, a token ceiling, tool allow/deny. Every
        // other condition kind belongs to a contributed compiler, and a kind nobody claims is refused
        // rather than compiled into a matcher that never fires. Refusal reaches an author on write,
        // through the Console's validateDsl; a matcher that never fires reaches nobody.
        for (ConditionCompiler contributor : conditionCompilers) {
            matchers.addAll(contributor.compile(conditions, rule));
        }

        String unclaimed = unclaimedConditions(conditions);
        if (unclaimed != null) {
            throw new PolicyCompilationException(
                    "Rule '" + rule.getId() + "' uses the condition '" + unclaimed + "', which is not "
                            + "available in this build. Conditions this build compiles: "
                            + String.join(", ", supportedConditionNames()) + ".",
                    null);
        }

        // A block whose conditions set nothing to check (an empty list, a max_tokens limit of zero or less, an
        // empty model or tools block) produces no matcher. Compiled, it would never fire, and the policy would
        // look active while checking nothing, so it is refused the same way an unclaimed condition is.
        if (matchers.isEmpty()) {
            throw new PolicyCompilationException(
                    "Rule '" + rule.getId() + "' has a `conditions:` block that sets nothing to check, so it "
                            + "could never fire. An empty allowlist or denylist, a `max_tokens` limit of zero "
                            + "or less, and an empty `model:` or `tools:` block each check nothing. Give the "
                            + "condition a value, or remove the rule.",
                    null);
        }
        if (matchers.size() == 1) {
            return matchers.getFirst();
        }
        // Composite: all conditions must match
        return (ctx, req) -> matchers.stream().allMatch(m -> m.matches(ctx, req));
    }

    /**
     * CEL is compiled by a contributor, not by this class. It is not a condition kind (it replaces
     * the whole {@code conditions:} block), so it is asked for separately, and a build with no
     * contributor that handles expressions refuses rather than compiling one that cannot evaluate.
     */
    private PolicyRule buildCelMatcher(RuleDsl rule) {
        for (ConditionCompiler contributor : conditionCompilers) {
            if (contributor.supportsExpressions()) {
                return contributor.compileExpression(rule);
            }
        }
        throw new PolicyCompilationException(
                "Rule '" + rule.getId() + "' uses `expression:` (CEL), which is not available in "
                        + "this build. Express the rule with a `conditions:` block instead — this "
                        + "build compiles: " + String.join(", ", supportedConditionNames()) + ".",
                null);
    }

    public static class PolicyCompilationException extends RuntimeException {
        public PolicyCompilationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}