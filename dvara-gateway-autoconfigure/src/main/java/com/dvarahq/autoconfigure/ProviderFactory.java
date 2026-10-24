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

import com.dvarahq.core.provider.LlmProvider;
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
import com.dvarahq.providers.ollama.OllamaProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import com.dvarahq.providers.qwen.QwenProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Builds a built-in provider while the gateway runs, wired exactly as the beans built at startup: the response size
 * limits, the TLS settings and the upstream quota tracking. A module that manages providers at runtime uses it with a
 * {@link com.dvarahq.core.provider.ProviderRegistry} of its own.
 *
 * <p>The key is never given here. Each call looks it up as {@code provider.<type>.api-key}, so the instance reads
 * the same credentials as a provider configured at startup.
 */
public class ProviderFactory {

    /** The types this factory builds: the built-in adapters, without the test provider. */
    public static final List<String> TYPES = List.of("openai", "anthropic", "gemini", "azure-openai", "mistral",
            "cohere", "groq", "qwen", "deepseek", "moonshot", "chatglm", "grok", "ollama", "bedrock");

    /**
     * What to build. {@code baseUrl} is read by every type but Bedrock, which reads {@code region}; a blank one means
     * the type's usual endpoint. {@code apiVersion} is Azure OpenAI's Responses API version, or blank.
     */
    public record Spec(String type, String baseUrl, String region, String apiVersion) {
    }

    private final ProviderAutoConfiguration wiring;
    private final GatewayProperties props;
    private final ObjectProvider<SecretProvider> secrets;
    private final ObjectProvider<RestClient.Builder> builders;

    ProviderFactory(ProviderAutoConfiguration wiring, GatewayProperties props, ObjectProvider<SecretProvider> secrets,
                    ObjectProvider<RestClient.Builder> builders) {
        this.wiring = wiring;
        this.props = props;
        this.secrets = secrets;
        this.builders = builders;
    }

    /**
     * A new provider for this spec.
     *
     * @throws IllegalArgumentException for an unknown type, or Azure OpenAI with no base URL
     */
    public LlmProvider create(Spec spec) {
        String type = spec.type() == null ? "" : spec.type().trim().toLowerCase(java.util.Locale.ROOT);
        String url = blank(spec.baseUrl()) ? null : spec.baseUrl().trim();
        RestClient.Builder base = builders.getIfAvailable(RestClient::builder);
        return switch (type) {
            case "openai" -> new OpenAiProvider(secret(), or(url, ProviderAutoConfiguration.OPENAI_BASE_URL),
                    keyed(type, base));
            case "anthropic" -> new AnthropicProvider(secret(), url, keyed(type, base),
                    props.getProviders().getAnthropic().getApiVersion());
            case "gemini" -> {
                GeminiProvider gemini = new GeminiProvider(secret(), or(url, ProviderAutoConfiguration.GEMINI_BASE_URL),
                        keyed(type, base));
                gemini.setImageFetcher(ProviderAutoConfiguration.imageFetcher(props));
                yield gemini;
            }
            case "azure-openai" -> {
                if (url == null) {
                    throw new IllegalArgumentException("Azure OpenAI needs a base URL.");
                }
                yield new AzureOpenAiProvider(secret(), url, keyed(type, base))
                        .responsesApiVersion(blank(spec.apiVersion()) ? null : spec.apiVersion().trim());
            }
            case "mistral" -> new MistralProvider(secret(), or(url, ProviderAutoConfiguration.MISTRAL_BASE_URL),
                    keyed(type, base));
            case "cohere" -> new CohereProvider(secret(), or(url, ProviderAutoConfiguration.COHERE_BASE_URL),
                    keyed(type, base));
            case "groq" -> new GroqProvider(secret(), or(url, ProviderAutoConfiguration.GROQ_BASE_URL),
                    keyed(type, base));
            case "qwen" -> new QwenProvider(secret(), or(url, ProviderAutoConfiguration.QWEN_BASE_URL),
                    keyed(type, base));
            case "deepseek" -> new DeepSeekProvider(secret(), or(url, ProviderAutoConfiguration.DEEPSEEK_BASE_URL),
                    keyed(type, base));
            case "moonshot" -> new MoonshotProvider(secret(), or(url, ProviderAutoConfiguration.MOONSHOT_BASE_URL),
                    keyed(type, base));
            case "chatglm" -> new ChatGlmProvider(secret(), or(url, ProviderAutoConfiguration.CHATGLM_BASE_URL),
                    keyed(type, base));
            case "grok" -> new GrokProvider(secret(), or(url, ProviderAutoConfiguration.GROK_BASE_URL),
                    keyed(type, base));
            case "ollama" -> {
                OllamaProvider ollama = new OllamaProvider(or(url, props.getProviders().getOllama().getBaseUrl()),
                        wiring.tlsOnly(type, base));
                ollama.setImageFetcher(ProviderAutoConfiguration.imageFetcher(props));
                ollama.setStructuredOutputs(props.getProviders().getOllama().isStructuredOutputs());
                yield ollama;
            }
            case "bedrock" -> {
                String region = blank(spec.region()) ? props.getProviders().getBedrock().getRegion() : spec.region().trim();
                BedrockProvider bedrock = new BedrockProvider(secret(), region, wiring.tlsOnly(type, base));
                bedrock.setImageFetcher(ProviderAutoConfiguration.imageFetcher(props));
                yield bedrock;
            }
            default -> throw new IllegalArgumentException("Unknown provider type '" + spec.type() + "'. Known: "
                    + String.join(", ", TYPES) + ".");
        };
    }

    private RestClient.Builder keyed(String type, RestClient.Builder base) {
        return wiring.clientFor(type, base, secret());
    }

    private SecretProvider secret() {
        SecretProvider secret = secrets.getIfAvailable();
        if (secret == null) {
            throw new IllegalStateException("No secret provider: a provider that takes a key cannot be built.");
        }
        return secret;
    }

    private static String or(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
