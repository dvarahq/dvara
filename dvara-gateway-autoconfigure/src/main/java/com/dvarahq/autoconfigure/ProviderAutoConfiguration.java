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
package com.dvarahq.autoconfigure;

import com.dvarahq.autoconfigure.tls.ProviderTlsCustomizer;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import com.dvarahq.providers.azureopenai.AzureOpenAiProvider;
import com.dvarahq.providers.bedrock.BedrockProvider;
import com.dvarahq.providers.chatglm.ChatGlmProvider;
import com.dvarahq.providers.cohere.CohereProvider;
import com.dvarahq.providers.deepseek.DeepSeekProvider;
import com.dvarahq.providers.gemini.GeminiProvider;
import com.dvarahq.providers.grok.GrokProvider;
import com.dvarahq.providers.groq.GroqProvider;
import com.dvarahq.providers.mistral.MistralProvider;
import com.dvarahq.providers.moonshot.MoonshotProvider;
import com.dvarahq.providers.qwen.QwenProvider;
import com.dvarahq.providers.mock.CompiledMatcher;
import com.dvarahq.providers.mock.MatcherEvaluator;
import com.dvarahq.providers.mock.MockMatcherTelemetry;
import com.dvarahq.providers.mock.MockProvider;
import com.dvarahq.providers.mock.MockScenarioLoader;
import com.dvarahq.providers.mock.MockScenarioWatcher;
import com.dvarahq.providers.ollama.OllamaProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import com.dvarahq.core.resilience.ProviderRateLimitTracker;
import com.dvarahq.providers.support.ProviderRateLimitInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
public class ProviderAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProviderAutoConfiguration.class);

    /** Spring profile names that suppress the "Mock enabled on non-dev profile" warning. */
    private static final Set<String> DEV_PROFILES = Set.of("dev", "test", "ci", "local", "default");

    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String MISTRAL_BASE_URL = "https://api.mistral.ai/v1";
    private static final String COHERE_BASE_URL = "https://api.cohere.com/v2";
    private static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    private static final String QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    private static final String MOONSHOT_BASE_URL = "https://api.moonshot.cn/v1";
    private static final String CHATGLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    private static final String GROK_BASE_URL = "https://api.x.ai/v1";

    /**
     * Per-credential upstream rate-limit tracker, applied to each secret-based provider's RestClient
     * as an outer interceptor by {@link #clientFor}.
     *
     * <p>Null when nothing tracks upstream quota, and then <b>no interceptor is installed at all</b>
     * rather than one that consults an always-allow tracker on every upstream call.
     */
    private final ProviderRateLimitTracker rateLimitTracker;
    /** Null when no module customises provider TLS. The builder is then used as it arrives. */
    private final ProviderTlsCustomizer tlsCustomizer;
    private final com.dvarahq.core.audit.AuditWriter rateLimitAuditWriter; // nullable — best-effort shed audit

    public ProviderAutoConfiguration(ObjectProvider<ProviderRateLimitTracker> rateLimitTracker,
                                     ObjectProvider<ProviderTlsCustomizer> tlsCustomizer,
                                     ObjectProvider<com.dvarahq.core.audit.AuditWriter> auditWriter) {
        // Resolved once. Absent means no interceptor and no customiser at all, rather than an
        // always-allow tracker or an identity customiser consulted on every call.
        this.rateLimitTracker = rateLimitTracker.getIfAvailable();
        this.tlsCustomizer = tlsCustomizer.getIfAvailable();
        this.rateLimitAuditWriter = auditWriter.getIfAvailable();
    }

    /**
     * TLS only, with no upstream quota tracking: Ollama, which has no credential, and Bedrock. Bedrock's
     * quota is per AWS key and region, but its credential is a key pair signed per request rather than one
     * {@code provider.<name>.api-key}, so the tracker, which keys on that single secret, is not applied.
     */
    private RestClient.Builder tlsOnly(String provider, RestClient.Builder restClientBuilder) {
        RestClient.Builder builder = restClientBuilder.clone();
        return tlsCustomizer == null ? builder : tlsCustomizer.customize(provider, builder);
    }

    /**
     * Wraps a provider's RestClient builder with the upstream rate-limit interceptor, keyed by
     * the provider's own credential ({@code provider.<name>.api-key}). The interceptor is <b>outer</b>
     * (added before the provider's own {@code CredentialInterceptor}), so it checks before the call and
     * observes the response headers.
     */
    private RestClient.Builder clientFor(String provider, RestClient.Builder restClientBuilder,
                                         SecretProvider secretProvider) {
        RestClient.Builder builder = restClientBuilder.clone();
        if (tlsCustomizer != null) {
            builder = tlsCustomizer.customize(provider, builder);
        }
        if (rateLimitTracker == null) {
            // No tracker, so no interceptor. An interceptor that consults an always-allow tracker
            // still runs on every upstream call and still decides nothing.
            return builder;
        }
        return builder.requestInterceptor(new ProviderRateLimitInterceptor(
                secretProvider, "provider." + provider + ".api-key", provider, rateLimitTracker,
                rateLimitAuditWriter));
    }

    /**
     * Register OpenAI only when the api-key property is present AND non-blank.
     * An empty string (e.g. {@code OPENAI_API_KEY=} unset) will not trigger registration,
     * giving the caller a clear 400 "no provider" error rather than a 502 from OpenAI.
     */
    @Bean
    @ConditionalOnMissingBean(OpenAiProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.openai.api-key:}')")
    public OpenAiProvider openAiProvider(GatewayProperties props, SecretProvider secretProvider,
                                         RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getOpenai();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : OPENAI_BASE_URL;
        RestClient.Builder builder = clientFor("openai", restClientBuilder, secretProvider);
        return new OpenAiProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(AnthropicProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.anthropic.api-key:}')")
    public AnthropicProvider anthropicProvider(SecretProvider secretProvider,
                                               RestClient.Builder restClientBuilder) {
        RestClient.Builder builder = clientFor("anthropic", restClientBuilder, secretProvider);
        return new AnthropicProvider(secretProvider, builder);
    }

    @Bean
    @ConditionalOnMissingBean(OllamaProvider.class)
    @ConditionalOnProperty(name = "dvara.llm-gateway.providers.ollama.enabled", havingValue = "true")
    public OllamaProvider ollamaProvider(GatewayProperties props,
                                          RestClient.Builder restClientBuilder) {
        RestClient.Builder builder = tlsOnly("ollama", restClientBuilder);
        return new OllamaProvider(props.getProviders().getOllama().getBaseUrl(), builder);
    }

    @Bean
    @ConditionalOnMissingBean(GeminiProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.gemini.api-key:}')")
    public GeminiProvider geminiProvider(GatewayProperties props, SecretProvider secretProvider,
                                         RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getGemini();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : GEMINI_BASE_URL;
        RestClient.Builder builder = clientFor("gemini", restClientBuilder, secretProvider);
        return new GeminiProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(BedrockProvider.class)
    @ConditionalOnProperty(name = "dvara.llm-gateway.providers.bedrock.enabled", havingValue = "true")
    public BedrockProvider bedrockProvider(GatewayProperties props, SecretProvider secretProvider,
                                           RestClient.Builder restClientBuilder) {
        GatewayProperties.BedrockConfig cfg = props.getProviders().getBedrock();
        RestClient.Builder builder = tlsOnly("bedrock", restClientBuilder);
        return new BedrockProvider(secretProvider, cfg.getRegion(), builder);
    }

    @Bean
    @ConditionalOnMissingBean(AzureOpenAiProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.azure-openai.api-key:}') " +
            "and T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.azure-openai.base-url:}')")
    public AzureOpenAiProvider azureOpenAiProvider(GatewayProperties props, SecretProvider secretProvider,
                                                    RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getAzureOpenai();
        RestClient.Builder builder = clientFor("azure-openai", restClientBuilder, secretProvider);
        return new AzureOpenAiProvider(secretProvider, cfg.getBaseUrl(), builder);
    }

    @Bean
    @ConditionalOnMissingBean(MistralProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.mistral.api-key:}')")
    public MistralProvider mistralProvider(GatewayProperties props, SecretProvider secretProvider,
                                           RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getMistral();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : MISTRAL_BASE_URL;
        RestClient.Builder builder = clientFor("mistral", restClientBuilder, secretProvider);
        return new MistralProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(CohereProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.cohere.api-key:}')")
    public CohereProvider cohereProvider(GatewayProperties props, SecretProvider secretProvider,
                                         RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getCohere();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : COHERE_BASE_URL;
        RestClient.Builder builder = clientFor("cohere", restClientBuilder, secretProvider);
        return new CohereProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(GroqProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.groq.api-key:}')")
    public GroqProvider groqProvider(GatewayProperties props, SecretProvider secretProvider,
                                     RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getGroq();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : GROQ_BASE_URL;
        RestClient.Builder builder = clientFor("groq", restClientBuilder, secretProvider);
        return new GroqProvider(secretProvider, baseUrl, builder);
    }

    // -------------------------------------------------------------------------
    // First-class OpenAI-compatible providers. Each follows the
    // exact same pattern as GroqProvider above — lazy-registered when the
    // corresponding api-key property is non-blank, default base URL pointing
    // at the canonical upstream endpoint.
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(QwenProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.qwen.api-key:}')")
    public QwenProvider qwenProvider(GatewayProperties props, SecretProvider secretProvider,
                                     RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getQwen();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : QWEN_BASE_URL;
        RestClient.Builder builder = clientFor("qwen", restClientBuilder, secretProvider);
        return new QwenProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(DeepSeekProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.deepseek.api-key:}')")
    public DeepSeekProvider deepSeekProvider(GatewayProperties props, SecretProvider secretProvider,
                                              RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getDeepseek();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : DEEPSEEK_BASE_URL;
        RestClient.Builder builder = clientFor("deepseek", restClientBuilder, secretProvider);
        return new DeepSeekProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(MoonshotProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.moonshot.api-key:}')")
    public MoonshotProvider moonshotProvider(GatewayProperties props, SecretProvider secretProvider,
                                              RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getMoonshot();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : MOONSHOT_BASE_URL;
        RestClient.Builder builder = clientFor("moonshot", restClientBuilder, secretProvider);
        return new MoonshotProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(ChatGlmProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.chatglm.api-key:}')")
    public ChatGlmProvider chatGlmProvider(GatewayProperties props, SecretProvider secretProvider,
                                            RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getChatglm();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : CHATGLM_BASE_URL;
        RestClient.Builder builder = clientFor("chatglm", restClientBuilder, secretProvider);
        return new ChatGlmProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(GrokProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.grok.api-key:}')")
    public GrokProvider grokProvider(GatewayProperties props, SecretProvider secretProvider,
                                      RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getGrok();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : GROK_BASE_URL;
        RestClient.Builder builder = clientFor("grok", restClientBuilder, secretProvider);
        return new GrokProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(MockProvider.class)
    @ConditionalOnProperty(name = "dvara.llm-gateway.providers.mock.enabled", havingValue = "true")
    public MockProvider mockProvider(GatewayProperties props, Environment environment,
                                     ObjectProvider<MockMatcherTelemetry> telemetryProvider) {
        warnIfMockEnabledOnProd(environment);
        GatewayProperties.MockConfig cfg = props.getProviders().getMock();

        // Precompile YAML matchers, so bad config fails at startup. File-backed scenarios are
        // loaded by mockScenarioWatcher, whose lifecycle Spring manages through its destroy method.
        List<GatewayProperties.MockMatcher> matcherConfigs = cfg.getMatchers();
        List<CompiledMatcher> compiled = new ArrayList<>(matcherConfigs.size());
        for (int i = 0; i < matcherConfigs.size(); i++) {
            compiled.add(compileMatcher(matcherConfigs.get(i), i));
        }

        MockProvider provider = new MockProvider(cfg.getResponse(), compiled,
                cfg.getLatencyMs(), cfg.getStreamTokenDelayMs(), cfg.getErrorRate());

        // Wire telemetry if a bean exists; otherwise the provider keeps its no-op default.
        MockMatcherTelemetry telemetry = telemetryProvider.getIfAvailable();
        if (telemetry != null) {
            provider.setTelemetry(telemetry);
        }

        return provider;
    }

    /**
     * Registers the mock scenario watcher as a Spring-managed bean with {@code close} as its destroy
     * method, so the {@link java.nio.file.WatchService} handle and its daemon thread are released
     * when the context shuts down. A JVM shutdown hook would accumulate across repeated
     * test-context bootstraps.
     *
     * <p>The watcher is started before the initial {@code loadAll()}, so a file created between the
     * two cannot be missed. A file present at startup may be loaded twice, once by each, and the two
     * loads produce the same matcher list.
     *
     * <p>Returns {@code null} when the scenarios directory is blank, missing or not a directory.
     * Spring treats a null bean as a placeholder, calls no destroy method, and the gateway continues
     * with YAML matchers only.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "dvara.llm-gateway.providers.mock.enabled", havingValue = "true")
    public MockScenarioWatcher mockScenarioWatcher(GatewayProperties props, MockProvider provider,
                                                    ObjectProvider<MockMatcherTelemetry> telemetryProvider) {
        Path scenariosDir = resolveScenariosDir(props.getProviders().getMock().getScenariosDir());
        if (scenariosDir == null) {
            return null;
        }
        MockScenarioWatcher watcher = new MockScenarioWatcher(scenariosDir, provider::replaceFileScenarios);
        MockMatcherTelemetry telemetry = telemetryProvider.getIfAvailable();
        if (telemetry != null) {
            watcher.setTelemetry(telemetry);
        }
        watcher.start();
        // Pick up files present at startup. A file created between watcher.start() and this
        // loadAll() is seen by both; a duplicate reload yields the same list.
        provider.replaceFileScenarios(MockScenarioLoader.loadAll(scenariosDir));
        return watcher;
    }

    /**
     * Resolves the configured {@code scenarios-dir} to an absolute path,
     * logging and returning {@code null} if the path is blank, missing,
     * or not a directory. This is intentionally forgiving: an absent or
     * typo'd directory should not abort gateway startup — it just means
     * no file scenarios, and the gateway continues with YAML matchers and
     * the default response.
     */
    static Path resolveScenariosDir(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path resolved = Paths.get(configured).toAbsolutePath();
        if (!java.nio.file.Files.isDirectory(resolved)) {
            log.warn("Mock scenarios directory '{}' does not exist or is not a directory — no file scenarios loaded",
                    resolved);
            return null;
        }
        log.info("Mock scenarios directory resolved to {}", resolved);
        return resolved;
    }

    /**
     * Precompiles a single configured matcher. Throws early with a clear error
     * message if either the predicate or required fields are missing, so the
     * gateway refuses to start with a broken matcher configuration rather than
     * failing lazily at request time.
     *
     * @param config the matcher configuration from {@code application.yml}
     * @param index  zero-based position in the {@code matchers} list, used
     *               as the default name when {@code config.name} is blank —
     *               stable across JVM restarts so log grep-ability is preserved
     */
    static CompiledMatcher compileMatcher(GatewayProperties.MockMatcher config, int index) {
        if (config.getWhen() == null || config.getWhen().isBlank()) {
            throw new IllegalArgumentException(
                    "Mock matcher at index " + index + " (name='" + config.getName()
                            + "') is missing the 'when' Groovy predicate");
        }
        if (config.getResponse() == null) {
            throw new IllegalArgumentException(
                    "Mock matcher at index " + index + " (name='" + config.getName()
                            + "') is missing the 'response' field");
        }
        String name = config.getName() != null && !config.getName().isBlank()
                ? config.getName()
                : "matcher-" + index;
        return new CompiledMatcher(name, new MatcherEvaluator(config.getWhen()), config.getResponse());
    }

    /**
     * Emits a WARN log when the Mock provider is activated on a Spring profile
     * that is not on the dev allow-list. Bundling Groovy means anyone who
     * flips {@code dvara.llm-gateway.providers.mock.enabled=true} in production can
     * execute arbitrary code inside the gateway JVM — this warning makes
     * accidental prod activation visible in log aggregation.
     *
     * <p>Suppresses the warning only when <b>every</b> active profile is on
     * the dev allow-list. Mixing a dev profile with a non-dev one (e.g.
     * {@code spring.profiles.active=dev,prod}) still triggers the warning —
     * the presence of a non-dev profile dominates.
     */
    static void warnIfMockEnabledOnProd(Environment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        boolean looksLikeDev = activeProfiles.length == 0
                || Arrays.stream(activeProfiles).map(String::toLowerCase).allMatch(DEV_PROFILES::contains);
        if (!looksLikeDev) {
            log.warn("Mock provider is enabled on profile(s) {}. Scripted mock responses allow arbitrary code "
                            + "execution in the gateway JVM. Disable dvara.llm-gateway.providers.mock.enabled for production.",
                    Arrays.toString(activeProfiles));
        }
    }
}