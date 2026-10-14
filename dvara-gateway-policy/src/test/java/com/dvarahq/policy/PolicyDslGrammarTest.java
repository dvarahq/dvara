package com.dvarahq.policy;

import com.dvarahq.policy.dsl.BudgetUtilizationConditionDsl;
import com.dvarahq.policy.dsl.ConditionDsl;
import com.dvarahq.policy.dsl.DataResidencyConditionDsl;
import com.dvarahq.policy.dsl.MaxTokensConditionDsl;
import com.dvarahq.policy.dsl.McpArgConditionDsl;
import com.dvarahq.policy.dsl.McpArgRuleDsl;
import com.dvarahq.policy.dsl.McpServerConditionDsl;
import com.dvarahq.policy.dsl.McpToolConditionDsl;
import com.dvarahq.policy.dsl.ModelConditionDsl;
import com.dvarahq.policy.dsl.PolicyDsl;
import com.dvarahq.policy.dsl.RuleDsl;
import com.dvarahq.policy.dsl.TimeOfDayConditionDsl;
import com.dvarahq.policy.dsl.TimeRangeDsl;
import com.dvarahq.policy.dsl.ToolsConditionDsl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The published grammar, policy-dsl/v1.schema.json, says exactly what the parser reads. */
class PolicyDslGrammarTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PolicyCompiler compiler = new PolicyCompiler();

    private static JsonNode schema() throws Exception {
        try (InputStream in = PolicyDslGrammarTest.class.getResourceAsStream("/policy-dsl/v1.schema.json")) {
            assertThat(in).as("the schema ships in the jar").isNotNull();
            return JSON.readTree(in);
        }
    }

    private static Set<String> keysOf(Class<?> type) {
        var description = JSON.getDeserializationConfig().introspect(JSON.constructType(type));
        Set<String> names = new TreeSet<>();
        description.findProperties().forEach(p -> names.add(p.getName()));
        return names;
    }

    private static Set<String> keysOf(JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void everyKeyTheParserReadsIsInTheSchema_andNoOther() throws Exception {
        JsonNode schema = schema();
        JsonNode defs = schema.path("$defs");
        JsonNode conditions = defs.path("conditions").path("properties");
        Map<Class<?>, JsonNode> pairs = new LinkedHashMap<>();
        pairs.put(PolicyDsl.class, schema);
        pairs.put(RuleDsl.class, defs.path("rule"));
        pairs.put(ConditionDsl.class, defs.path("conditions"));
        pairs.put(ModelConditionDsl.class, conditions.path("model"));
        pairs.put(MaxTokensConditionDsl.class, conditions.path("max_tokens"));
        pairs.put(ToolsConditionDsl.class, conditions.path("tools"));
        pairs.put(DataResidencyConditionDsl.class, conditions.path("data_residency"));
        pairs.put(TimeOfDayConditionDsl.class, conditions.path("time_of_day"));
        pairs.put(TimeRangeDsl.class, conditions.path("time_of_day").path("properties").path("deny_hours").path("items"));
        pairs.put(McpServerConditionDsl.class, conditions.path("mcp_server"));
        pairs.put(McpToolConditionDsl.class, conditions.path("mcp_tool"));
        pairs.put(McpArgConditionDsl.class, conditions.path("mcp_arg"));
        pairs.put(McpArgRuleDsl.class, conditions.path("mcp_arg").path("properties").path("rules").path("items"));
        pairs.put(BudgetUtilizationConditionDsl.class, conditions.path("budget_utilization"));

        pairs.forEach((type, node) -> assertThat(keysOf(node)).as(type.getSimpleName()).isEqualTo(keysOf(type)));
    }

    @Test
    void theReservedKeysAreTheSameInTheSchemaAndTheCompiler() throws Exception {
        Set<String> published = new TreeSet<>();
        schema().path("x-reserved-keys").forEach(k -> published.add(k.asText()));
        assertThat(published).isEqualTo(new TreeSet<>(PolicyCompiler.RESERVED_KEYS));
    }

    @Test
    void aReservedKeyIsRefusedAsReserved_notAsATypo() {
        assertThatThrownBy(() -> compiler.compileDsl("""
                version: "1"
                rules:
                  - id: r
                    action: DENY
                    fail_mode: CLOSED
                    conditions:
                      model: {denylist: [gpt-4o]}
                """))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("'fail_mode' is reserved for a later version");
    }

    @Test
    void version1IsReadAndAnyOtherIsRefused() {
        String rules = """
                rules:
                  - id: r
                    conditions:
                      model: {denylist: [gpt-4o]}
                """;
        for (String version : new String[]{"", "version: \"1\"\n", "version: 1\n", "version: \"1.0\"\n"}) {
            assertThat(compiler.compileDsl(version + rules).rules()).as(version).hasSize(1);
        }
        assertThatThrownBy(() -> compiler.compileDsl("version: \"2\"\n" + rules))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("version '2' is not supported");
    }
}
