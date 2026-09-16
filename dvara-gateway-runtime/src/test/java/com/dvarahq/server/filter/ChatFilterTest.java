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
package com.dvarahq.server.filter;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.guardrail.GroundingConfig;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.prompt.PromptTemplateResolver;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.security.SecurityContext;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.server.metrics.GatewayMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatFilterTest {

    // --- TemplateResolutionFilter ---

    @Test
    void templateFilter_delegatesToResolver() {
        PromptTemplateResolver resolver = mock(PromptTemplateResolver.class);
        ChatRequest resolved = ChatRequest.builder().model("resolved").build();
        when(resolver.resolve(any(ChatRequest.class), any(String.class))).thenReturn(resolved);

        var filter = new TemplateResolutionFilter(resolver);
        assertThat(filter.order()).isEqualTo(FilterOrder.TEMPLATE_RESOLUTION);

        ChatRequest result = filter.preDispatch(
                ChatRequest.builder().model("original").build(),
                FilterContext.builder().workspaceId("t1").build());
        assertThat(result.getModel()).isEqualTo("resolved");
    }

    // --- PolicyEnforcementFilter ---

    @Test
    void policyFilter_allowed_setsPolicyDecision() {
        PolicyEngine engine = mock(PolicyEngine.class);
        when(engine.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);
        AuditWriter audit = mock(AuditWriter.class);
        SecurityContext sec = mock(SecurityContext.class);
        when(sec.currentUserId()).thenReturn(Optional.empty());
        RegionContext region = mock(RegionContext.class);
        when(region.currentRegion()).thenReturn(Optional.empty());

        var filter = new PolicyEnforcementFilter(engine, audit, sec, region, Optional.empty());
        assertThat(filter.order()).isEqualTo(FilterOrder.POLICY_ENFORCEMENT);

        FilterContext ctx = FilterContext.builder().workspaceId("t1").build();
        filter.preDispatch(ChatRequest.builder().model("gpt-4o").build(), ctx);

        assertThat(ctx.getPolicyDecision()).isEqualTo(PolicyDecision.ALLOW);
    }

    @Test
    void policyFilter_denied_throws() {
        PolicyEngine engine = mock(PolicyEngine.class);
        when(engine.evaluate(any(), any())).thenReturn(PolicyDecision.deny("blocked", "p1", "r1"));
        AuditWriter audit = mock(AuditWriter.class);
        SecurityContext sec = mock(SecurityContext.class);
        when(sec.currentUserId()).thenReturn(Optional.empty());
        RegionContext region = mock(RegionContext.class);
        when(region.currentRegion()).thenReturn(Optional.empty());

        var filter = new PolicyEnforcementFilter(engine, audit, sec, region, Optional.empty());

        assertThatThrownBy(() -> filter.preDispatch(
                ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").apiKey("key-id-1").build()))
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "POLICY_DENIED");

        verify(audit).write(argThat(e -> {
            var p = e.payload();
            return "POLICY_DENIED".equals(e.eventType())
                    && "key-id-1".equals(p.get("api_key"))   // the key id, whole — it is not a secret
                    && p.containsKey("user_id");
        }));
    }

    @Test
    void policyFilter_injectsRegionIntoContext() {
        PolicyEngine engine = mock(PolicyEngine.class);
        when(engine.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);
        AuditWriter audit = mock(AuditWriter.class);
        SecurityContext sec = mock(SecurityContext.class);
        when(sec.currentUserId()).thenReturn(Optional.empty());
        RegionContext region = mock(RegionContext.class);
        when(region.currentRegion()).thenReturn(Optional.of("us-east-1"));

        var filter = new PolicyEnforcementFilter(engine, audit, sec, region, Optional.empty());
        FilterContext ctx = FilterContext.builder().workspaceId("t1").build();
        filter.preDispatch(ChatRequest.builder().model("gpt-4o").build(), ctx);

        verify(engine).evaluate(argThat(policyCtx ->
                "us-east-1".equals(policyCtx.attributes().get("region"))), any());
    }

    /** The filter resolves {@code context.workspace_region} from the workspace's own row, so a rule keyed on it can match. */
    @Test
    void policyFilter_injectsWorkspaceRegionFromTheWorkspaceRow() {
        PolicyEngine engine = mock(PolicyEngine.class);
        when(engine.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);
        AuditWriter audit = mock(AuditWriter.class);
        SecurityContext sec = mock(SecurityContext.class);
        when(sec.currentUserId()).thenReturn(Optional.empty());
        RegionContext region = mock(RegionContext.class);
        when(region.currentRegion()).thenReturn(Optional.empty());
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById("t1")).thenReturn(Optional.of(
                Workspace.builder().id("t1").name("Acme").region("eu-west-1").build()));

        var filter = new PolicyEnforcementFilter(engine, audit, sec, region, Optional.of(workspaces));
        filter.preDispatch(ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").build());

        verify(engine).evaluate(argThat(policyCtx ->
                "eu-west-1".equals(policyCtx.attributes().get("workspace_region"))), any());
    }

    /**
     * The value must come from the workspace row and <b>never</b> from the caller's request body —
     * otherwise a rule {@code context.workspace_region == "eu"} is satisfiable by the party the rule
     * is applied to. Same reasoning as {@code injectResolvedWorkspace} overwriting
     * {@code metadata.workspace_id}.
     */
    @Test
    void policyFilter_ignoresACallerSuppliedWorkspaceRegion() {
        PolicyEngine engine = mock(PolicyEngine.class);
        when(engine.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);
        AuditWriter audit = mock(AuditWriter.class);
        SecurityContext sec = mock(SecurityContext.class);
        when(sec.currentUserId()).thenReturn(Optional.empty());
        RegionContext region = mock(RegionContext.class);
        when(region.currentRegion()).thenReturn(Optional.empty());
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById("t1")).thenReturn(Optional.of(
                Workspace.builder().id("t1").name("Acme").region("us-east-1").build()));

        var filter = new PolicyEnforcementFilter(engine, audit, sec, region, Optional.of(workspaces));
        filter.preDispatch(
                ChatRequest.builder().model("gpt-4o")
                        .metadata(Map.of("workspace_region", "eu-west-1"))
                        .build(),
                FilterContext.builder().workspaceId("t1").build());

        verify(engine).evaluate(argThat(policyCtx ->
                "us-east-1".equals(policyCtx.attributes().get("workspace_region"))), any());
    }

    /** No workspace, no repository, or a row with no region — the attribute is simply absent, so
     *  the CEL binding is "" and an {@code == "eu"} rule fails closed. */
    @Test
    void policyFilter_omitsWorkspaceRegionWhenTheRowHasNone() {
        PolicyEngine engine = mock(PolicyEngine.class);
        when(engine.evaluate(any(), any())).thenReturn(PolicyDecision.ALLOW);
        AuditWriter audit = mock(AuditWriter.class);
        SecurityContext sec = mock(SecurityContext.class);
        when(sec.currentUserId()).thenReturn(Optional.empty());
        RegionContext region = mock(RegionContext.class);
        when(region.currentRegion()).thenReturn(Optional.empty());
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById("t1")).thenReturn(Optional.of(
                Workspace.builder().id("t1").name("Acme").region("   ").build()));

        var filter = new PolicyEnforcementFilter(engine, audit, sec, region, Optional.of(workspaces));
        filter.preDispatch(ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").build());

        verify(engine).evaluate(argThat(policyCtx ->
                !policyCtx.attributes().containsKey("workspace_region")), any());
    }

    // --- PiiEnforcementFilter ---

    @Test
    void piiFilter_preDispatch_delegatesToEnforcer() {
        PiiEnforcer enforcer = mock(PiiEnforcer.class);
        when(enforcer.enforceRequest(any(), any())).thenAnswer(i -> i.getArgument(0));

        var filter = new PiiEnforcementFilter(enforcer);
        assertThat(filter.order()).isEqualTo(FilterOrder.PII_ENFORCEMENT);

        filter.preDispatch(ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").build());
        verify(enforcer).enforceRequest(any(), eq("t1"));
    }

    @Test
    void piiFilter_postDispatch_delegatesToEnforcer() {
        PiiEnforcer enforcer = mock(PiiEnforcer.class);
        when(enforcer.enforceResponse(any(), any())).thenAnswer(i -> i.getArgument(0));

        var filter = new PiiEnforcementFilter(enforcer);
        filter.postDispatch(ChatRequest.builder().build(),
                ChatResponse.builder().build(),
                FilterContext.builder().workspaceId("t1").build());
        verify(enforcer).enforceResponse(any(), eq("t1"));
    }

    // --- GuardrailEnforcementFilter ---

    @Test
    void guardrailFilter_delegatesToEnforcer() {
        GuardrailEnforcer enforcer = mock(GuardrailEnforcer.class);
        when(enforcer.enforceRequest(any(), any())).thenAnswer(i -> i.getArgument(0));

        var filter = new GuardrailEnforcementFilter(enforcer);
        assertThat(filter.order()).isEqualTo(FilterOrder.GUARDRAIL_ENFORCEMENT);

        filter.preDispatch(ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").build());
        verify(enforcer).enforceRequest(any(), eq("t1"));
    }

    @Test
    void guardrailFilter_postDispatch_passesOriginalRequest() {
        GuardrailEnforcer enforcer = mock(GuardrailEnforcer.class);
        when(enforcer.enforceResponse(any(ChatResponse.class), any(), any(ChatRequest.class)))
                .thenAnswer(i -> i.getArgument(0));

        var filter = new GuardrailEnforcementFilter(enforcer);
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        ChatResponse response = ChatResponse.builder().build();

        filter.postDispatch(request, response, FilterContext.builder().workspaceId("t1").build());
        verify(enforcer).enforceResponse(eq(response), eq("t1"), eq(request));
    }

    // --- GroundingDetectionFilter ---

    @Test
    void groundingFilter_disabled_skips() {
        GroundingDetector detector = mock(GroundingDetector.class);
        GroundingConfig config = new GroundingConfig(false, GuardrailAction.LOG);
        AuditWriter audit = mock(AuditWriter.class);

        var filter = new GroundingDetectionFilter(detector, config, audit, mock(GatewayMetrics.class), mock(com.dvarahq.core.workspace.WorkspaceRepository.class));
        assertThat(filter.order()).isEqualTo(FilterOrder.GROUNDING_DETECTION);

        filter.postDispatch(ChatRequest.builder().build(),
                ChatResponse.builder().build(),
                FilterContext.builder().build());
        verify(detector, never()).check(any(), any(), any());
    }

    @Test
    void groundingFilter_enabled_blockOnHallucination() {
        GroundingDetector detector = mock(GroundingDetector.class);
        when(detector.check(any(), any(), any())).thenReturn(
                new GroundingResult(false, 0.4, List.of("ungrounded claim"), 0.3));
        GroundingConfig config = new GroundingConfig(true, GuardrailAction.BLOCK);
        AuditWriter audit = mock(AuditWriter.class);

        var filter = new GroundingDetectionFilter(detector, config, audit, mock(GatewayMetrics.class), mock(com.dvarahq.core.workspace.WorkspaceRepository.class));

        assertThatThrownBy(() -> filter.postDispatch(
                ChatRequest.builder().metadata(Map.of("grounding.sources", List.of("source doc"))).build(),
                ChatResponse.builder().build(),
                FilterContext.builder().workspaceId("t1").build()))
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "HALLUCINATION_DETECTED");

        verify(audit).write(any());
    }

    @Test
    void groundingFilter_noSources_skips() {
        GroundingDetector detector = mock(GroundingDetector.class);
        GroundingConfig config = new GroundingConfig(true, GuardrailAction.BLOCK);
        AuditWriter audit = mock(AuditWriter.class);

        var filter = new GroundingDetectionFilter(detector, config, audit, mock(GatewayMetrics.class), mock(com.dvarahq.core.workspace.WorkspaceRepository.class));
        filter.postDispatch(
                ChatRequest.builder().metadata(Map.of()).build(),
                ChatResponse.builder().build(),
                FilterContext.builder().build());
        verify(detector, never()).check(any(), any(), any());
    }

    @Test
    void groundingFilter_capsSourcesByMaxSources() {
        GroundingDetector detector = mock(GroundingDetector.class);
        when(detector.check(any(), any(), any())).thenReturn(GroundingResult.GROUNDED);
        GroundingConfig config = new GroundingConfig(true, GuardrailAction.LOG, 2, 0);
        AuditWriter audit = mock(AuditWriter.class);

        var filter = new GroundingDetectionFilter(detector, config, audit, mock(GatewayMetrics.class), mock(com.dvarahq.core.workspace.WorkspaceRepository.class));
        filter.postDispatch(
                ChatRequest.builder().metadata(Map.of("grounding.sources",
                        List.of("source1", "source2", "source3", "source4"))).build(),
                ChatResponse.builder().build(),
                FilterContext.builder().build());

        // maxSources is 2, so only two of the four reach the detector.
        verify(detector).check(any(), any(), argThat(sources -> sources.size() == 2));
    }

    @Test
    void groundingFilter_filtersOversizedSources() {
        GroundingDetector detector = mock(GroundingDetector.class);
        when(detector.check(any(), any(), any())).thenReturn(GroundingResult.GROUNDED);
        GroundingConfig config = new GroundingConfig(true, GuardrailAction.LOG, 50, 10);
        AuditWriter audit = mock(AuditWriter.class);

        var filter = new GroundingDetectionFilter(detector, config, audit, mock(GatewayMetrics.class), mock(com.dvarahq.core.workspace.WorkspaceRepository.class));
        filter.postDispatch(
                ChatRequest.builder().metadata(Map.of("grounding.sources",
                        List.of("short", "this is way too long to pass the filter"))).build(),
                ChatResponse.builder().build(),
                FilterContext.builder().build());

        // Only "short" fits the 10-character limit; the long source is dropped.
        verify(detector).check(any(), any(), argThat(sources ->
                sources.size() == 1 && sources.get(0).equals("short")));
    }

    @Test
    void groundingFilter_perWorkspaceOverride_disablesGrounding() {
        GroundingDetector detector = mock(GroundingDetector.class);
        GroundingConfig config = new GroundingConfig(true, GuardrailAction.BLOCK);
        AuditWriter audit = mock(AuditWriter.class);
        com.dvarahq.core.workspace.WorkspaceRepository workspaceRepo = mock(com.dvarahq.core.workspace.WorkspaceRepository.class);

        // Workspace metadata disables grounding
        com.dvarahq.core.workspace.Workspace workspace = com.dvarahq.core.workspace.Workspace.builder()
                .id("t1").metadata(Map.of("grounding.enabled", "false")).build();
        when(workspaceRepo.findById("t1")).thenReturn(Optional.of(workspace));

        var filter = new GroundingDetectionFilter(detector, config, audit, mock(GatewayMetrics.class), workspaceRepo);
        filter.postDispatch(
                ChatRequest.builder().metadata(Map.of("grounding.sources", List.of("source doc"))).build(),
                ChatResponse.builder().build(),
                FilterContext.builder().workspaceId("t1").build());

        // The workspace override disables grounding, so the detector is never called.
        verify(detector, never()).check(any(), any(), any());
    }

    @Test
    void groundingFilter_perWorkspaceOverride_changesAction() {
        GroundingDetector detector = mock(GroundingDetector.class);
        when(detector.check(any(), any(), any())).thenReturn(
                new GroundingResult(false, 0.4, List.of("ungrounded claim"), 0.3));
        GroundingConfig config = new GroundingConfig(true, GuardrailAction.BLOCK);
        AuditWriter audit = mock(AuditWriter.class);
        com.dvarahq.core.workspace.WorkspaceRepository workspaceRepo = mock(com.dvarahq.core.workspace.WorkspaceRepository.class);

        // The workspace overrides the global BLOCK action to LOG, so the ungrounded result does not throw.
        com.dvarahq.core.workspace.Workspace workspace = com.dvarahq.core.workspace.Workspace.builder()
                .id("t1").metadata(Map.of("grounding.action", "LOG")).build();
        when(workspaceRepo.findById("t1")).thenReturn(Optional.of(workspace));

        var filter = new GroundingDetectionFilter(detector, config, audit, mock(GatewayMetrics.class), workspaceRepo);
        filter.postDispatch(
                ChatRequest.builder().metadata(Map.of("grounding.sources", List.of("source doc"))).build(),
                ChatResponse.builder().build(),
                FilterContext.builder().workspaceId("t1").build());

        verify(detector).check(any(), any(), any());
    }

    // --- ContextWindowFilter ---

    @Test
    void contextWindowFilter_withinLimits_passesThrough() {
        var governor = mock(com.dvarahq.core.guardrail.ContextWindowGovernor.class);
        when(governor.evaluate(any(), anyInt(), any())).thenReturn(
                com.dvarahq.core.guardrail.ContextWindowResult.withinLimits(1000, 128000));
        GatewayMetrics m = mock(GatewayMetrics.class);

        var filter = new ContextWindowFilter(governor, m, providersOf());
        assertThat(filter.order()).isEqualTo(FilterOrder.CONTEXT_WINDOW);

        ChatRequest req = ChatRequest.builder().model("gpt-4o").build();
        ChatRequest result = filter.preDispatch(req, FilterContext.builder().workspaceId("t1").build());
        assertThat(result).isSameAs(req);
    }

    @Test
    void contextWindowFilter_exceeds_throws() {
        var governor = mock(com.dvarahq.core.guardrail.ContextWindowGovernor.class);
        var cwResult = new com.dvarahq.core.guardrail.ContextWindowResult(150000, 128000, 117, false, true, null);
        when(governor.evaluate(any(), anyInt(), any())).thenReturn(cwResult);
        GatewayMetrics m = mock(GatewayMetrics.class);

        var filter = new ContextWindowFilter(governor, m, providersOf());

        assertThatThrownBy(() -> filter.preDispatch(
                ChatRequest.builder().model("gpt-4o").build(),
                FilterContext.builder().workspaceId("t1").build()))
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "CONTEXT_WINDOW_EXCEEDED");
    }


    // --- ContextWindowFilter: the window comes from the provider, not from a literal ---

    /** An {@link org.springframework.beans.factory.ObjectProvider} over a fixed set, as Spring gives. */
    private static org.springframework.beans.factory.ObjectProvider<com.dvarahq.core.provider.LlmProvider>
            providersOf(com.dvarahq.core.provider.LlmProvider... providers) {
        var beanFactory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        for (int i = 0; i < providers.length; i++) {
            beanFactory.addBean("provider" + i, providers[i]);
        }
        return beanFactory.getBeanProvider(com.dvarahq.core.provider.LlmProvider.class);
    }

    private static com.dvarahq.core.provider.LlmProvider providerWithWindow(String name, int maxContextTokens,
                                                                           boolean claimsTheModel) {
        var provider = mock(com.dvarahq.core.provider.LlmProvider.class);
        when(provider.name()).thenReturn(name);
        when(provider.supports(any())).thenReturn(claimsTheModel);
        when(provider.capabilities()).thenReturn(new com.dvarahq.core.provider.ProviderCapabilities(
                true, false, false, false, false, maxContextTokens));
        return provider;
    }

    private static int windowPassedToGovernor(com.dvarahq.core.provider.LlmProvider... providers) {
        var governor = mock(com.dvarahq.core.guardrail.ContextWindowGovernor.class);
        when(governor.evaluate(any(), anyInt(), any())).thenReturn(
                com.dvarahq.core.guardrail.ContextWindowResult.withinLimits(10, 1));
        var filter = new ContextWindowFilter(governor, mock(GatewayMetrics.class), providersOf(providers));

        filter.preDispatch(ChatRequest.builder().model("llama3").build(),
                FilterContext.builder().workspaceId("t1").build());

        var captor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(governor).evaluate(any(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void contextWindowFilter_usesTheWindowDeclaredByTheProviderThatWillServeIt() {
        // 32,000 is the window this provider declares; the governor must see that, not a fixed default.
        assertThat(windowPassedToGovernor(providerWithWindow("ollama", 32_000, true))).isEqualTo(32_000);
    }

    @Test
    void contextWindowFilter_ignoresProvidersThatDoNotClaimTheModel() {
        assertThat(windowPassedToGovernor(
                providerWithWindow("ollama", 32_000, false),
                providerWithWindow("gemini", 1_000_000, true)))
                .isEqualTo(1_000_000);
    }

    @Test
    void contextWindowFilter_whenSeveralProvidersClaimTheModel_takesTheSmallestWindow() {
        // The request can fail over to any of them, so only the smallest window holds for all.
        assertThat(windowPassedToGovernor(
                providerWithWindow("openai", 128_000, true),
                providerWithWindow("deepseek", 64_000, true)))
                .isEqualTo(64_000);
    }

    @Test
    void contextWindowFilter_noProviderClaimsTheModel_fallsBackRatherThanTreatingItAsUnlimited() {
        assertThat(windowPassedToGovernor(providerWithWindow("openai", 128_000, false)))
                .isEqualTo(ContextWindowFilter.FALLBACK_MAX_CONTEXT_TOKENS);
    }

    @Test
    void contextWindowFilter_aProviderThatThrowsWhileMatchingIsNotACandidate() {
        var throwing = mock(com.dvarahq.core.provider.LlmProvider.class);
        when(throwing.name()).thenReturn("broken");
        when(throwing.supports(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(windowPassedToGovernor(throwing, providerWithWindow("ollama", 32_000, true)))
                .isEqualTo(32_000);
    }

    // --- OutputSchemaFilter ---

    @Test
    void outputSchemaFilter_valid_passesThrough() {
        var validator = mock(com.dvarahq.core.guardrail.OutputSchemaValidator.class);
        when(validator.validate(any(), any(), any(), any())).thenReturn(
                com.dvarahq.core.guardrail.OutputSchemaResult.VALID);
        GatewayMetrics m = mock(GatewayMetrics.class);

        var filter = new OutputSchemaFilter(validator, m);
        assertThat(filter.order()).isEqualTo(FilterOrder.OUTPUT_SCHEMA);

        ChatResponse result = filter.postDispatch(
                ChatRequest.builder().model("gpt-4o").build(),
                ChatResponse.builder().build(),
                FilterContext.builder().workspaceId("t1").build());
        assertThat(result).isNotNull();
    }

    @Test
    void outputSchemaFilter_invalid_throws() {
        var validator = mock(com.dvarahq.core.guardrail.OutputSchemaValidator.class);
        when(validator.validate(any(), any(), any(), any())).thenReturn(
                com.dvarahq.core.guardrail.OutputSchemaResult.invalid(List.of("missing field"), "s1"));
        GatewayMetrics m = mock(GatewayMetrics.class);

        var filter = new OutputSchemaFilter(validator, m);

        assertThatThrownBy(() -> filter.postDispatch(
                ChatRequest.builder().model("gpt-4o").build(),
                ChatResponse.builder().build(),
                FilterContext.builder().workspaceId("t1").build()))
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "SCHEMA_VALIDATION_FAILED");
    }
}