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

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.ContextWindowGovernor;
import com.dvarahq.core.guardrail.AdditionalGuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailEnforcer;
import com.dvarahq.core.guardrail.OutputSchemaRepository;
import com.dvarahq.core.guardrail.OutputSchemaValidator;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.pii.AdditionalPiiDetector;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.pii.PiiTokenizationService;
import com.dvarahq.core.guardrail.GuardrailMetricsListener;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.policy.PolicyRepository;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.policy.streaming.StreamingResponseEnforcerImpl;
import com.dvarahq.policy.guardrail.DefaultContextWindowGovernor;
import com.dvarahq.policy.guardrail.DefaultOutputSchemaValidator;
import com.dvarahq.policy.guardrail.TiktokenEstimator;
import com.dvarahq.policy.guardrail.CompositeGuardrailDetector;
import com.dvarahq.policy.guardrail.ContentFilterDetector;
import com.dvarahq.policy.guardrail.ContentPatternRegistry;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import com.dvarahq.policy.guardrail.GuardrailScanService;
import com.dvarahq.policy.guardrail.InjectionDetector;
import com.dvarahq.policy.guardrail.InjectionPatternRegistry;
import com.dvarahq.core.guardrail.MlClassifierHook;
import com.dvarahq.policy.guardrail.SystemPromptLeakDetector;
import com.dvarahq.policy.pii.CompositePiiDetector;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.pii.PiiProvider;
import com.dvarahq.policy.pii.PiiScanService;
import com.dvarahq.pii.RegexPiiDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import com.dvarahq.core.workspace.settings.WorkspaceSettingEntryRepository;
import com.dvarahq.core.workspace.settings.GuardrailSettingsRepository;
import com.dvarahq.core.workspace.settings.PiiSettingsRepository;
import com.dvarahq.core.workspace.settings.ContentRuleRepository;
import com.dvarahq.core.enforcement.StreamingPostureProvider;
import com.dvarahq.core.enforcement.StreamingEnforcementTelemetry;
import com.dvarahq.core.enforcement.StreamingEnforcementEngine;
import java.util.ArrayList;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

@AutoConfiguration
@EnableConfigurationProperties({PiiProperties.class, GuardrailProperties.class})
public class PolicyAutoConfiguration {

    /** Mirrors PiiProperties, so "set at all" can be distinguished from "left alone". */
    private static final int DEFAULT_MAX_TOKENS_PER_WORKSPACE = 50000;

    private static final Logger log = LoggerFactory.getLogger(PolicyAutoConfiguration.class);



    /**
     * The compiler, built from whatever condition kinds this deployment contributes.
     *
     * <p>An ObjectProvider rather than a required list: with no contributor on the classpath the
     * compiler handles model, max_tokens and tools, and refuses anything else by name at compile
     * time, so a condition that cannot be compiled never becomes a matcher that never fires.</p>
     */
    @Bean
    public PolicyCompiler policyCompiler(ObjectProvider<ConditionCompiler> conditionCompilers) {
        List<ConditionCompiler> contributors = conditionCompilers.orderedStream().toList();
        if (!contributors.isEmpty()) {
            log.info("Policy conditions contributed by {} compiler(s)", contributors.size());
        }
        return new PolicyCompiler(contributors);
    }

    @Bean
    @ConditionalOnMissingBean(PolicyEngine.class)
    public DefaultPolicyEngine defaultPolicyEngine(PolicyRepository policyRepository,
                                                  AuditWriter auditWriter,
                                                  PolicyCompiler policyCompiler) {
        // Stands down when an application registered another PolicyEngine first. There is no
        // always-allow default: an assembly with the request path and without an engine fails at
        // construction instead of silently serving ungoverned requests.
        var engine = new DefaultPolicyEngine(policyRepository, auditWriter, policyCompiler);
        engine.rebuildIndex();
        return engine;
    }

    @Bean
    public PiiPatternRegistry piiPatternRegistry() {
        return new PiiPatternRegistry();
    }

    /*
     * No PiiTokenizationService bean is registered here. This build has no reversible token store:
     * REDACT replaces values irreversibly and stores nothing, and TOKENIZE is refused rather than
     * downgraded. A null tokenizer is how PiiScanService knows to refuse; a stand-in that accepted
     * tokens and dropped the mapping would turn that refusal into silent irreversibility.
     */

    /**
     * Pattern-and-checksum detection, layered with whatever else this deployment supplies. This is
     * the composite, and the bean every consumer of a {@code PiiDetector} should receive.
     *
     * <p>The regex detector always runs; a build with no extra layers detects less, not nothing.
     * Contributions arrive as {@link AdditionalPiiDetector}, a marker rather than a plain
     * {@code PiiDetector}, because this method produces a {@code PiiDetector} and would otherwise
     * collect the thing it is building. The startup line names the posture so an operator can tell
     * whether a layer they configured is actually running.</p>
     *
     * <p>{@code @Primary}, and deliberately not {@code @ConditionalOnMissingBean(PiiDetector.class)}:
     * {@code AdditionalPiiDetector} extends {@code PiiDetector}, so a single contribution would
     * satisfy that condition and, depending on auto-configuration order, either suppress this
     * composite (taking the regex detector with it) or leave two candidates with no primary.
     * Customisation is additive: register an {@code AdditionalPiiDetector} and this composite
     * collects it. The regex layer is not optional.</p>
     */
    @Bean
    @Primary
    public PiiDetector piiDetector(PiiPatternRegistry piiPatternRegistry,
                                    PiiProperties piiProperties,
                                    ObjectProvider<AdditionalPiiDetector> additionalDetectors) {
        RegexPiiDetector regexDetector = new RegexPiiDetector(piiPatternRegistry);

        var detectors = new ArrayList<PiiDetector>();
        detectors.add(regexDetector);
        detectors.addAll(additionalDetectors.orderedStream().toList());

        // The endpoint is the real toggle; the provider property is an alias for it. The case that
        // matters is asking for the presidio provider and never setting the endpoint: nothing to
        // call, silent fallback to patterns, and an operator who believes NER is running.
        String requestedProvider = piiProperties.getProvider();
        boolean presidioEndpointSet = piiProperties.getPresidio() != null
                && piiProperties.getPresidio().getEndpoint() != null
                && !piiProperties.getPresidio().getEndpoint().isBlank();
        if (PiiProvider.fromString(requestedProvider) == PiiProvider.PRESIDIO && !presidioEndpointSet) {
            log.warn("dvara.llm-gateway.pii.provider=presidio, but dvara.llm-gateway.pii.presidio"
                    + ".endpoint is not set, so there is nothing to call and no NER detection is "
                    + "running. The endpoint is what activates the layer; this property is an alias "
                    + "for it. Pattern and checksum detection is unaffected.");
        }
        // fromString swallows an unrecognised value and answers REGEX, which is the safe fallback and
        // an invisible one: "presideo" reads exactly like a deliberate choice of patterns-only.
        if (requestedProvider != null && !requestedProvider.isBlank()
                && !"regex".equalsIgnoreCase(requestedProvider)
                && !"presidio".equalsIgnoreCase(requestedProvider)) {
            log.warn("dvara.llm-gateway.pii.provider='{}' is not a recognised provider (regex, "
                    + "presidio). Falling back to regex — if you meant to enable NER, set "
                    + "dvara.llm-gateway.pii.presidio.endpoint.", requestedProvider);
        }

        // Nothing here acts on these settings; they bind so every build reads them at the same paths.
        // An operator who sets an NER endpoint or an embedded filter list and sees a clean startup
        // would believe advanced detection is running, so this says whether a detector registered.
        Set<String> layers = additionalDetectors.orderedStream()
                .map(AdditionalPiiDetector::providesLayer)
                .filter(l -> l != null && !l.isBlank())
                .map(l -> l.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        {
            // Per layer, not "did anything contribute at all": an unrelated detector must not
            // silence the warning about a different layer the operator configured and is not getting.
            if (presidioEndpointSet && !layers.contains("presidio")) {
                log.warn("dvara.llm-gateway.pii.presidio.endpoint is set, but no NER detector is "
                        + "part of this build — it will NOT be called. Pattern and checksum "
                        + "detection is unaffected.");
            }
            if (piiProperties.getEmbedded() != null && piiProperties.getEmbedded().isEnabled()
                    && !layers.contains("embedded")) {
                log.warn("dvara.llm-gateway.pii.embedded.enabled is set, but the embedded scanner "
                        + "is not part of this build — it will NOT run. Pattern and checksum "
                        + "detection is unaffected.");
            }
        }

        log.info("PII detection posture: regex{}", detectors.size() == 1
                ? " only (no additional detectors on the classpath)"
                : " + " + (detectors.size() - 1) + " layered detector(s)");

        return detectors.size() == 1 ? regexDetector
                : new CompositePiiDetector(detectors);
    }

    @Bean
    @ConditionalOnMissingBean(PiiEnforcer.class)
    public PiiEnforcer piiEnforcer(PiiDetector piiDetector,
                                    ObjectProvider<PiiTokenizationService> piiTokenization,
                                    AuditWriter auditWriter, WorkspaceRepository workspaceRepository,
                                    PiiProperties piiProperties,
                                    ObjectProvider<
                                            PiiSettingsRepository> settings) {
        // ObjectProvider, not a required dependency: the typed settings store registers only with a
        // datasource, and this bean has to keep working without one, where the resolver falls
        // through to the workspace metadata map.
        //
        // Auto-detokenize with a default action other than TOKENIZE is a combination that cannot do
        // anything, so it is said at startup rather than discovered as an absence. Detokenizing
        // restores values from tokens, and only TOKENIZE mints one; under REDACT the original is
        // gone by construction, so nothing is restored and no error is ever raised.
        //
        // Only the install-wide pair is checked here. A workspace can set either half itself, and
        // its resolved setting is not known until a request arrives; warning per request would be
        // noise on the hot path.
        if (piiProperties.isAutoDetokenizeResponse()
                && piiProperties.getDefaultAction() != PiiAction.TOKENIZE) {
            log.warn("dvara.llm-gateway.pii.auto-detokenize-response is on, but the default PII "
                    + "action is {} — nothing will be detokenized. Only TOKENIZE mints a reversible "
                    + "token; REDACT replaces the value irreversibly and stores nothing, so there "
                    + "is no original to restore. Set dvara.llm-gateway.pii.default-action=TOKENIZE "
                    + "(the behaviour REDACT had before 1.8.0), or turn auto-detokenize off.",
                    piiProperties.getDefaultAction());
        }

        // A token cap sizes a vault this build does not have. Distinct from the auto-detokenize
        // warning above: that combination cannot work anywhere, while this setting only does
        // something when a module supplies a reversible token store.
        if (piiTokenization.getIfAvailable() == null
                && piiProperties.getMaxTokensPerWorkspace() != DEFAULT_MAX_TOKENS_PER_WORKSPACE) {
            log.warn("dvara.llm-gateway.pii.max-tokens-per-workspace is set to {}, but this build "
                    + "has no reversible token store to bound — nothing is stored, so nothing is "
                    + "capped. REDACT is irreversible and needs no vault.",
                    piiProperties.getMaxTokensPerWorkspace());
        }

        // ObjectProvider for the tokenizer too: this build registers no implementation, so a
        // required parameter would refuse to start a context whose PII controls (detect, LOG,
        // BLOCK, REDACT) are entirely functional without one.
        return new PiiScanService(piiDetector, piiTokenization.getIfAvailable(), auditWriter,
                workspaceRepository, piiProperties, settings.getIfAvailable());
    }

    @Bean
    public InjectionPatternRegistry injectionPatternRegistry() {
        return new InjectionPatternRegistry();
    }


    @Bean
    public ContentPatternRegistry contentPatternRegistry() {
        return new ContentPatternRegistry();
    }

    @Bean
    public ContentFilterDetector contentFilterDetector(ContentPatternRegistry contentPatternRegistry,
                                                        WorkspaceRepository workspaceRepository,
            ObjectProvider<ContentRuleRepository> workspaceRules,
            ObjectProvider<WorkspaceSettingEntryRepository> settingEntriesForContent,
            ObjectProvider<GuardrailSettingsRepository> guardrailSettingsForContent) {
        var detector = new ContentFilterDetector(contentPatternRegistry, workspaceRepository,
                workspaceRules.getIfAvailable());
        detector.setSettingEntries(settingEntriesForContent.getIfAvailable());
        detector.setGuardrailSettings(guardrailSettingsForContent.getIfAvailable());
        return detector;
    }

    /**
     * The composite, and the bean every consumer of a {@code GuardrailDetector} should receive.
     *
     * <p>{@code @Primary} because the layers this composes (content filtering, injection patterns,
     * system-prompt leakage) are {@code GuardrailDetector}s registered as beans in their own right,
     * so the type alone cannot say which one a consumer wants. Not
     * {@code @ConditionalOnMissingBean(GuardrailDetector.class)}: one of its own layers would
     * satisfy that condition, and a single layer would then become the whole guardrail.
     *
     * <p>To add a detector rather than replace the set, register an
     * {@code AdditionalGuardrailDetector}; this composite collects them.
     */
    @Bean
    @Primary
    public GuardrailDetector guardrailDetector(InjectionPatternRegistry injectionPatternRegistry,
                                                ObjectProvider<MlClassifierHook> mlClassifierHook,
                                                ContentFilterDetector contentFilterDetector,
                                                WorkspaceRepository workspaceRepository,
                                                ObjectProvider<AdditionalGuardrailDetector> additionalDetectors,
                                                ObjectProvider<
                                                        WorkspaceSettingEntryRepository>
                                                        settingEntriesForDetectors) {
        InjectionDetector injectionDetector = new InjectionDetector(injectionPatternRegistry,
                mlClassifierHook.getIfAvailable(), workspaceRepository);
        injectionDetector.setSettingEntries(settingEntriesForDetectors.getIfAvailable());

        var detectors = new ArrayList<GuardrailDetector>();
        detectors.add(injectionDetector);
        detectors.add(contentFilterDetector);

        // Detectors contributed from outside this module. Additive: the injection patterns and
        // content filters above run regardless, so a build with no contributions scans for fewer
        // things rather than for nothing.
        detectors.addAll(additionalDetectors.orderedStream().toList());

        return new CompositeGuardrailDetector(detectors);
    }

    @Bean
    @ConditionalOnMissingBean(OutputSchemaValidator.class)
    public OutputSchemaValidator outputSchemaValidator(OutputSchemaRepository outputSchemaRepository) {
        return new DefaultOutputSchemaValidator(outputSchemaRepository);
    }

    @Bean
    @ConditionalOnMissingBean(TokenEstimator.class)
    public TokenEstimator tokenEstimator() {
        return new TiktokenEstimator();
    }

    @Bean
    @ConditionalOnMissingBean(ContextWindowGovernor.class)
    public ContextWindowGovernor contextWindowGovernor(TokenEstimator tokenEstimator,
                                                        AuditWriter auditWriter,
                                                        WorkspaceRepository workspaceRepository) {
        return new DefaultContextWindowGovernor(tokenEstimator, auditWriter, workspaceRepository);
    }

    @Bean
    @Primary
    public StreamingEnforcementEngine streamingEnforcementEngine(
            PiiDetector piiDetector,
            GuardrailDetector guardrailDetector,
            ObjectProvider<com.dvarahq.core.guardrail.GroundingDetector>
                    groundingDetectorProvider) {
        // Optional: grounding needs an embedding model, and a build without one has no detector at
        // all rather than one that answers "grounded" to everything. The engine refuses when the
        // posture asks for grounding it cannot perform.
        return new com.dvarahq.policy.streaming.DefaultStreamingEnforcementEngine(
                piiDetector, guardrailDetector, groundingDetectorProvider.getIfAvailable());
    }

    @Bean
    @Primary
    public StreamingPostureProvider streamingPostureProvider(
            WorkspaceRepository workspaceRepository,
            PiiProperties piiProperties,
            GuardrailProperties guardrailProperties,
            com.dvarahq.core.guardrail.GroundingConfig groundingConfig,
            ObjectProvider<PiiSettingsRepository> piiSettings,
            ObjectProvider<GuardrailSettingsRepository> guardrailSettings) {
        return new com.dvarahq.policy.streaming.DefaultStreamingPostureProvider(
                workspaceRepository, piiSettings.getIfAvailable(), guardrailSettings.getIfAvailable(),
                piiProperties, guardrailProperties, groundingConfig);
    }

    @Bean
    @ConditionalOnMissingBean(StreamingResponseEnforcer.class)
    public StreamingResponseEnforcer streamingResponseEnforcer(AuditWriter auditWriter,
                                                                PiiProperties piiProperties,
                                                                GuardrailProperties guardrailProperties,
                                                                StreamingEnforcementEngine streamingEngine,
                                                                StreamingPostureProvider postureProvider,
                                                                ObjectProvider<StreamingEnforcementTelemetry> telemetry) {
        return new StreamingResponseEnforcerImpl(auditWriter, piiProperties, guardrailProperties,
                streamingEngine, postureProvider,
                telemetry.getIfAvailable(() ->
                        StreamingEnforcementTelemetry.NOOP));
    }

    @Bean
    @ConditionalOnMissingBean(GuardrailEnforcer.class)
    public GuardrailEnforcer guardrailEnforcer(GuardrailDetector guardrailDetector,
                                                AuditWriter auditWriter,
                                                WorkspaceRepository workspaceRepository,
                                                GuardrailProperties guardrailProperties,
                                                ObjectProvider<GuardrailMetricsListener> guardrailMetricsListener,
                                                ObjectProvider<
                                                        GuardrailSettingsRepository> guardrailSettings,
                                                ObjectProvider<WorkspaceSettingEntryRepository> settingEntries) {
        GuardrailScanService service = new GuardrailScanService(guardrailDetector, auditWriter,
                workspaceRepository, guardrailProperties, new SystemPromptLeakDetector(),
                guardrailMetricsListener);
        // ObjectProvider: the typed store registers only with a datasource, and this bean must keep
        // working without one, falling through to the workspace metadata map.
        service.setGuardrailSettings(guardrailSettings.getIfAvailable());
        service.setSettingEntries(settingEntries.getIfAvailable());
        return service;
    }
}
