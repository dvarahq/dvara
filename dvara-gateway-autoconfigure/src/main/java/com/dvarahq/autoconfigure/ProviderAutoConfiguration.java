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
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
public class ProviderAutoConfiguration implements org.springframework.context.EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(ProviderAutoConfiguration.class);

    /** Spring profile names that suppress the "Mock enabled on non-dev profile" warning. */
    private static final Set<String> DEV_PROFILES = Set.of("dev", "test", "ci", "local", "default");

    static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    static final String GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
    static final String MISTRAL_BASE_URL = "https://api.mistral.ai/v1";
    static final String COHERE_BASE_URL = "https://api.cohere.com/v2";
    static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    static final String QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1";
    static final String MOONSHOT_BASE_URL = "https://api.moonshot.cn/v1";
    static final String CHATGLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    static final String GROK_BASE_URL = "https://api.x.ai/v1";

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
    /** Read when a provider's client is built, for the response size limits and the upstream client settings. */
    private final ObjectProvider<GatewayProperties> properties;
    /** The application's {@code spring.http.clients.*} settings; the upstream client starts from them. */
    private final ObjectProvider<HttpClientSettings> httpClientSettings;
    /** One upstream client for every provider, built on first use. Its connection pool is shared. */
    private volatile ClientHttpRequestFactory upstreamRequestFactory;
    /**
     * The executor the JDK client runs its work on: virtual threads when the application runs on them.
     * Spring Boot gives its own JDK client the same; a client built here would otherwise fall back to
     * the JDK's cached pool of platform threads, which grew to hundreds of threads under load.
     */
    private java.util.concurrent.Executor upstreamExecutor;

    @Override
    public void setEnvironment(Environment environment) {
        this.upstreamExecutor = org.springframework.boot.thread.Threading.VIRTUAL.isActive(environment)
                ? new org.springframework.core.task.VirtualThreadTaskExecutor("httpclient-")
                : null;
    }

    public ProviderAutoConfiguration(ObjectProvider<ProviderRateLimitTracker> rateLimitTracker,
                                     ObjectProvider<ProviderTlsCustomizer> tlsCustomizer,
                                     ObjectProvider<com.dvarahq.core.audit.AuditWriter> auditWriter,
                                     ObjectProvider<GatewayProperties> properties,
                                     ObjectProvider<HttpClientSettings> httpClientSettings) {
        // Resolved once. Absent means no interceptor and no customiser at all, rather than an
        // always-allow tracker or an identity customiser consulted on every call.
        this.rateLimitTracker = rateLimitTracker.getIfAvailable();
        this.tlsCustomizer = tlsCustomizer.getIfAvailable();
        this.rateLimitAuditWriter = auditWriter.getIfAvailable();
        this.properties = properties;
        this.httpClientSettings = httpClientSettings;
    }

    /**
     * The HTTP client every provider calls through: the JDK client, chosen here rather than left to
     * whichever client library happens to be on the classpath. Left to the classpath, a build that
     * carried Apache HttpComponents got its default pool of 5 connections per host, so calls to one
     * provider queued behind each other well before the CPU was busy. The JDK client keeps idle
     * connections to each host it calls and opens another whenever all are busy.
     */
    ClientHttpRequestFactory upstreamRequestFactory() {
        ClientHttpRequestFactory factory = upstreamRequestFactory;
        if (factory == null) {
            synchronized (this) {
                factory = upstreamRequestFactory;
                if (factory == null) {
                    GatewayProperties props = properties.getIfAvailable(GatewayProperties::new);
                    factory = upstreamRequestFactory(props.getUpstreamHttp(), httpClientSettings.getIfAvailable(),
                            upstreamExecutor);
                    upstreamRequestFactory = factory;
                }
            }
        }
        return factory;
    }

    static final java.time.Duration DEFAULT_UPSTREAM_CONNECT_TIMEOUT = java.time.Duration.ofSeconds(10);

    /**
     * A setting under {@code dvara.llm-gateway.upstream-http} wins; else {@code spring.http.clients.*};
     * else a 10 s connect timeout and no read timeout, since a slow model can take minutes to answer.
     */
    static ClientHttpRequestFactory upstreamRequestFactory(GatewayProperties.UpstreamHttpConfig config,
                                                           HttpClientSettings applicationSettings) {
        return upstreamRequestFactory(config, applicationSettings, null);
    }

    /** As above, with the executor the client runs its work on; {@code null} keeps the JDK's default. */
    static ClientHttpRequestFactory upstreamRequestFactory(GatewayProperties.UpstreamHttpConfig config,
                                                           HttpClientSettings applicationSettings,
                                                           java.util.concurrent.Executor executor) {
        HttpClientSettings settings = applicationSettings != null ? applicationSettings : HttpClientSettings.defaults();
        java.time.Duration connect = firstNonNull(config.getConnectTimeout(), settings.connectTimeout(),
                DEFAULT_UPSTREAM_CONNECT_TIMEOUT);
        java.time.Duration read = firstNonNull(config.getReadTimeout(), settings.readTimeout(), null);
        JdkClientHttpRequestFactoryBuilder builder = ClientHttpRequestFactoryBuilder.jdk();
        if (executor != null) {
            builder = builder.withExecutor(executor);
        }
        java.net.http.HttpClient.Version version = config.getVersion();
        if (version != null) {
            builder = builder.withHttpClientCustomizer(client -> client.version(version));
        }
        return builder.build(settings.withTimeouts(connect, read));
    }

    private static java.time.Duration firstNonNull(java.time.Duration first, java.time.Duration second,
                                                   java.time.Duration fallback) {
        return first != null ? first : second != null ? second : fallback;
    }

    /**
     * Puts the provider on the shared upstream client and bounds the bytes read from its responses.
     * Every provider client is built through {@link #clientFor} or {@link #tlsOnly}, so this is the one
     * place both are applied. A TLS customiser that needs a client of its own replaces it afterwards.
     */
    private RestClient.Builder sizeLimited(String provider, RestClient.Builder builder) {
        builder.requestFactory(upstreamRequestFactory());
        GatewayProperties props = properties.getIfAvailable(GatewayProperties::new);
        GatewayProperties.ResponseLimitsConfig limits = props.getResponseLimits();
        if (limits.getMaxBodyBytes() <= 0 && limits.getMaxStreamBytes() <= 0) {
            return builder;
        }
        return builder.requestInterceptor(new com.dvarahq.providers.support.ResponseSizeLimitInterceptor(
                provider, limits.getMaxBodyBytes(), limits.getMaxStreamBytes()));
    }

    /**
     * TLS only, with no upstream quota tracking: Ollama, which has no credential, and Bedrock. Bedrock's
     * quota is per AWS key and region, but its credential is a key pair signed per request rather than one
     * {@code provider.<name>.api-key}, so the tracker, which keys on that single secret, is not applied.
     */
    RestClient.Builder tlsOnly(String provider, RestClient.Builder restClientBuilder) {
        RestClient.Builder builder = sizeLimited(provider, restClientBuilder.clone());
        return tlsCustomizer == null ? builder : tlsCustomizer.customize(provider, builder);
    }

    /**
     * Wraps a provider's RestClient builder with the upstream rate-limit interceptor, keyed by
     * the provider's own credential ({@code provider.<name>.api-key}). The interceptor is <b>outer</b>
     * (added before the provider's own {@code CredentialInterceptor}), so it checks before the call and
     * observes the response headers.
     */
    RestClient.Builder clientFor(String provider, RestClient.Builder restClientBuilder,
                                         SecretProvider secretProvider) {
        RestClient.Builder builder = sizeLimited(provider, restClientBuilder.clone());
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
     * The providers registered at startup, read once on first use. A module that adds, replaces or removes providers
     * while the gateway runs registers its own {@link com.dvarahq.core.provider.ProviderRegistry} instead.
     */
    @Bean
    @ConditionalOnMissingBean(com.dvarahq.core.provider.ProviderRegistry.class)
    public com.dvarahq.core.provider.ProviderRegistry providerRegistry(
            ObjectProvider<com.dvarahq.core.provider.LlmProvider> providers) {
        return com.dvarahq.core.provider.ProviderRegistry.lazily(() -> providers.orderedStream().toList());
    }

    /** Builds a provider at runtime, wired as the startup beans are: response limits, TLS and quota tracking. */
    @Bean
    @ConditionalOnMissingBean(ProviderFactory.class)
    public ProviderFactory providerFactory(GatewayProperties props, ObjectProvider<SecretProvider> secretProvider,
                                           ObjectProvider<RestClient.Builder> restClientBuilder) {
        return new ProviderFactory(this, props, secretProvider, restClientBuilder);
    }

    /**
     * The model context windows written in {@code dvara.llm-gateway.model-limits}. Always registered,
     * and answers nothing until an entry is written. It is one source among possibly several: the
     * context-window check asks every {@link com.dvarahq.core.provider.ModelContextLimits} bean in
     * order, and this one comes first so that configuration wins.
     */
    @Bean
    public com.dvarahq.autoconfigure.guardrail.ConfiguredModelContextLimits configuredModelContextLimits(
            GatewayProperties props) {
        return new com.dvarahq.autoconfigure.guardrail.ConfiguredModelContextLimits(props.getModelLimits());
    }

    /**
     * A provider that takes an API key registers when its {@code enabled} switch is on, or when its api-key
     * property has text. The switch lets a provider be on while its key lives only in a secret store: the key is
     * read on each call, never from this condition. A provider with neither gives the caller a clear 400
     * "no provider" error rather than a 502 from upstream. The same rule holds for every key-based provider below.
     */
    @Bean
    @ConditionalOnMissingBean(OpenAiProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.openai.api-key:}') "
            + "or '${dvara.llm-gateway.providers.openai.enabled:false}' == 'true'")
    public OpenAiProvider openAiProvider(GatewayProperties props, SecretProvider secretProvider,
                                         RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getOpenai();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : OPENAI_BASE_URL;
        RestClient.Builder builder = clientFor("openai", restClientBuilder, secretProvider);
        return new OpenAiProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(AnthropicProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.anthropic.api-key:}') "
            + "or '${dvara.llm-gateway.providers.anthropic.enabled:false}' == 'true'")
    public AnthropicProvider anthropicProvider(GatewayProperties props, SecretProvider secretProvider,
                                               RestClient.Builder restClientBuilder) {
        RestClient.Builder builder = clientFor("anthropic", restClientBuilder, secretProvider);
        return new AnthropicProvider(secretProvider, props.getProviders().getAnthropic().getBaseUrl(), builder);
    }

    @Bean
    @ConditionalOnMissingBean(OllamaProvider.class)
    @ConditionalOnExpression("'${dvara.llm-gateway.providers.ollama.enabled:false}' == 'true' "
            + "or '${dvara.llm-gateway.providers.ollama.per-workspace:false}' == 'true'")
    public OllamaProvider ollamaProvider(GatewayProperties props,
                                          RestClient.Builder restClientBuilder,
                                          org.springframework.beans.factory.ObjectProvider<com.dvarahq.providers.ollama.OllamaEndpointResolver> endpoints) {
        RestClient.Builder builder = tlsOnly("ollama", restClientBuilder);
        OllamaProvider provider = new OllamaProvider(props.getProviders().getOllama().getBaseUrl(), builder);
        provider.setImageFetcher(imageFetcher(props));
        provider.setStructuredOutputs(props.getProviders().getOllama().isStructuredOutputs());
        if (props.getProviders().getOllama().isPerWorkspace()) {
            // #30 A6: each workspace's own endpoint. A client that follows no redirect, and not the platform
            // TLS settings, which are for the platform's providers, not a tenant's endpoint.
            java.net.http.HttpClient.Builder httpBuilder = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .connectTimeout(java.time.Duration.ofSeconds(10));
            if (upstreamExecutor != null) {
                httpBuilder.executor(upstreamExecutor);
            }
            java.net.http.HttpClient http = httpBuilder.build();
            provider.usePerWorkspaceEndpoints(endpoints.getIfAvailable(), sizeLimited("ollama", restClientBuilder.clone())
                    .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory(http))
                    .build());
        }
        return provider;
    }

    @Bean
    @ConditionalOnMissingBean(GeminiProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.gemini.api-key:}') "
            + "or '${dvara.llm-gateway.providers.gemini.enabled:false}' == 'true'")
    public GeminiProvider geminiProvider(GatewayProperties props, SecretProvider secretProvider,
                                         RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getGemini();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : GEMINI_BASE_URL;
        RestClient.Builder builder = clientFor("gemini", restClientBuilder, secretProvider);
        GeminiProvider provider = new GeminiProvider(secretProvider, baseUrl, builder);
        provider.setImageFetcher(imageFetcher(props));
        return provider;
    }

    @Bean
    @ConditionalOnMissingBean(BedrockProvider.class)
    @ConditionalOnProperty(name = "dvara.llm-gateway.providers.bedrock.enabled", havingValue = "true")
    public BedrockProvider bedrockProvider(GatewayProperties props, SecretProvider secretProvider,
                                           RestClient.Builder restClientBuilder) {
        GatewayProperties.BedrockConfig cfg = props.getProviders().getBedrock();
        RestClient.Builder builder = tlsOnly("bedrock", restClientBuilder);
        BedrockProvider provider = new BedrockProvider(secretProvider, cfg.getRegion(), builder);
        provider.setImageFetcher(imageFetcher(props));
        return provider;
    }

    /** The image-URL fetcher for providers that take bytes only (#55); the disabled one unless turned on. */
    static com.dvarahq.providers.support.ImageFetcher imageFetcher(GatewayProperties props) {
        GatewayProperties.ImageFetchConfig cfg = props.getImageFetch();
        if (!cfg.isEnabled()) {
            return com.dvarahq.providers.support.ImageFetcher.DISABLED;
        }
        return new com.dvarahq.providers.support.ImageFetcher(true, cfg.getMaxBytes(), cfg.getTimeout(),
                java.util.Set.copyOf(cfg.getContentTypes()));
    }

    @Bean
    @ConditionalOnMissingBean(AzureOpenAiProvider.class)
    @ConditionalOnExpression("(T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.azure-openai.api-key:}') "
            + "or '${dvara.llm-gateway.providers.azure-openai.enabled:false}' == 'true') "
            + "and T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.azure-openai.base-url:}')")
    public AzureOpenAiProvider azureOpenAiProvider(GatewayProperties props, SecretProvider secretProvider,
                                                    RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getAzureOpenai();
        RestClient.Builder builder = clientFor("azure-openai", restClientBuilder, secretProvider);
        return new AzureOpenAiProvider(secretProvider, cfg.getBaseUrl(), builder)
                .responsesApiVersion(cfg.getResponsesApiVersion());
    }

    @Bean
    @ConditionalOnMissingBean(MistralProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.mistral.api-key:}') "
            + "or '${dvara.llm-gateway.providers.mistral.enabled:false}' == 'true'")
    public MistralProvider mistralProvider(GatewayProperties props, SecretProvider secretProvider,
                                           RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getMistral();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : MISTRAL_BASE_URL;
        RestClient.Builder builder = clientFor("mistral", restClientBuilder, secretProvider);
        return new MistralProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(CohereProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.cohere.api-key:}') "
            + "or '${dvara.llm-gateway.providers.cohere.enabled:false}' == 'true'")
    public CohereProvider cohereProvider(GatewayProperties props, SecretProvider secretProvider,
                                         RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getCohere();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : COHERE_BASE_URL;
        RestClient.Builder builder = clientFor("cohere", restClientBuilder, secretProvider);
        return new CohereProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(GroqProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.groq.api-key:}') "
            + "or '${dvara.llm-gateway.providers.groq.enabled:false}' == 'true'")
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
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.qwen.api-key:}') "
            + "or '${dvara.llm-gateway.providers.qwen.enabled:false}' == 'true'")
    public QwenProvider qwenProvider(GatewayProperties props, SecretProvider secretProvider,
                                     RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getQwen();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : QWEN_BASE_URL;
        RestClient.Builder builder = clientFor("qwen", restClientBuilder, secretProvider);
        return new QwenProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(DeepSeekProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.deepseek.api-key:}') "
            + "or '${dvara.llm-gateway.providers.deepseek.enabled:false}' == 'true'")
    public DeepSeekProvider deepSeekProvider(GatewayProperties props, SecretProvider secretProvider,
                                              RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getDeepseek();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : DEEPSEEK_BASE_URL;
        RestClient.Builder builder = clientFor("deepseek", restClientBuilder, secretProvider);
        return new DeepSeekProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(MoonshotProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.moonshot.api-key:}') "
            + "or '${dvara.llm-gateway.providers.moonshot.enabled:false}' == 'true'")
    public MoonshotProvider moonshotProvider(GatewayProperties props, SecretProvider secretProvider,
                                              RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getMoonshot();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : MOONSHOT_BASE_URL;
        RestClient.Builder builder = clientFor("moonshot", restClientBuilder, secretProvider);
        return new MoonshotProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(ChatGlmProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.chatglm.api-key:}') "
            + "or '${dvara.llm-gateway.providers.chatglm.enabled:false}' == 'true'")
    public ChatGlmProvider chatGlmProvider(GatewayProperties props, SecretProvider secretProvider,
                                            RestClient.Builder restClientBuilder) {
        GatewayProperties.ProviderConfig cfg = props.getProviders().getChatglm();
        String baseUrl = cfg.getBaseUrl() != null ? cfg.getBaseUrl() : CHATGLM_BASE_URL;
        RestClient.Builder builder = clientFor("chatglm", restClientBuilder, secretProvider);
        return new ChatGlmProvider(secretProvider, baseUrl, builder);
    }

    @Bean
    @ConditionalOnMissingBean(GrokProvider.class)
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${dvara.llm-gateway.providers.grok.api-key:}') "
            + "or '${dvara.llm-gateway.providers.grok.enabled:false}' == 'true'")
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
        provider.setImageTokens(cfg.getImageTokens(), cfg.getImageTokensLow());

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