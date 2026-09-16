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
package com.dvarahq.policy.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class ConditionDsl {
    private ModelConditionDsl model;

    @JsonProperty("max_tokens")
    private MaxTokensConditionDsl maxTokens;

    private ToolsConditionDsl tools;

    @JsonProperty("data_residency")
    private DataResidencyConditionDsl dataResidency;

    @JsonProperty("time_of_day")
    private TimeOfDayConditionDsl timeOfDay;

    @JsonProperty("mcp_server")
    private McpServerConditionDsl mcpServer;

    @JsonProperty("mcp_tool")
    private McpToolConditionDsl mcpTool;

    @JsonProperty("mcp_arg")
    private McpArgConditionDsl mcpArg;

    @JsonProperty("budget_utilization")
    private BudgetUtilizationConditionDsl budgetUtilization;
}