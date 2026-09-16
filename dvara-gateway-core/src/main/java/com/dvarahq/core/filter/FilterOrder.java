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
package com.dvarahq.core.filter;

/**
 * Standard ordering constants for {@link ChatFilter} implementations.
 * Lower values execute first in pre-dispatch, last in post-dispatch.
 * Leave gaps of 100 between steps to allow insertion of custom filters.
 *
 * <p>{@code PROMPT_EXPERIMENT}, {@code BUDGET_ENFORCEMENT}, {@code PER_CALL_COST},
 * {@code TOKEN_CAP_ENFORCEMENT}, {@code PRIORITY_ADMISSION} and {@code MODEL_DOWNGRADE} name filters
 * this build does not include; another module or the application may register them. The numbers
 * are kept so that a filter sits in the same position wherever it runs and a custom filter can be
 * placed relative to them. A constant here is not evidence that the filter runs: what runs is
 * whatever is registered as a {@link ChatFilter} bean.
 */
public final class FilterOrder {

    private FilterOrder() {}

    /** A filter another module registers ahead of template resolution; unused in this build. */
    public static final int PROMPT_EXPERIMENT = 50;

    public static final int TEMPLATE_RESOLUTION = 100;
    /**
     * {@code WorkspaceStatusFilter}: runs before everything else that touches a store (budget, cap,
     * policy) so a SUSPENDED workspace is rejected without running the rest of the chain.
     */
    public static final int WORKSPACE_STATUS_ENFORCEMENT = 150;
    public static final int BUDGET_ENFORCEMENT = 200;
    /** Per-call max-cost gate: after the accumulated-spend budget cap, before token caps. */
    public static final int PER_CALL_COST = 225;
    public static final int TOKEN_CAP_ENFORCEMENT = 250;
    public static final int POLICY_ENFORCEMENT = 300;
    public static final int PII_ENFORCEMENT = 400;
    public static final int GUARDRAIL_ENFORCEMENT = 500;
    public static final int PRIORITY_ADMISSION = 600;
    public static final int GROUNDING_DETECTION = 700;
    public static final int MODEL_DOWNGRADE = 800;
    public static final int CONTEXT_WINDOW = 900;
    public static final int OUTPUT_SCHEMA = 1000;
}