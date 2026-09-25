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
package com.dvarahq.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assembled application serves from one {@code gateway.yaml}, with no database and no control
 * plane: a key in the file is served, a policy in the file denies, a PII action in a workspace's
 * metadata blocks or redacts, the file's rate limit is enforced, a prompt template is rendered, and
 * a governance decision lands in a verifiable audit record.
 *
 * <p>A 200 is also what an ungoverned gateway returns, so each feature is paired with the request it
 * refuses: remove a block from the file and its test fails.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
class OpenBuildServesFromFileTest {

    @TempDir
    static Path configDir;

    /**
     * One key per test. The rate limiter counts per key and the file allows three a minute, so a
     * shared key would spend another test's budget and fail it with a 429.
     */
    static final String SERVE_KEY = "dvara-open-build-serve-key";
    static final String POLICY_KEY = "dvara-open-build-policy-key";
    static final String PII_KEY = "dvara-open-build-pii-key";
    static final String BURST_KEY = "dvara-open-build-burst-key";
    static final String TIGHT_KEY = "dvara-open-build-tight-key";
    static final String PROMPT_KEY = "dvara-open-build-prompt-key";
    static final String REDACT_KEY = "dvara-open-build-redact-key";

    /**
     * The file is pointed at with a system property rather than a {@code @DynamicPropertySource}:
     * {@code GatewayConfigEnvironmentPostProcessor} reads it before the context exists, and a
     * dynamic property source is registered too late for it to see.
     */
    @BeforeAll
    static void writeGatewayYaml() throws IOException {
        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock

                workspaces:
                  - id: acme
                    name: Acme
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: BLOCK
                  - id: tight
                    name: Tight
                    status: ACTIVE
                    metadata:
                      rate-limit.requests-per-minute: 1
                  - id: redactor
                    name: Redactor
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: REDACT

                rate_limits:
                  requests_per_minute: 3
                  tokens_per_minute: 1000000

                api_keys:
                  - key_hash: sha256:%s
                    workspace: acme
                    name: serve-key
                  - key_hash: sha256:%s
                    workspace: acme
                    name: policy-key
                  - key_hash: sha256:%s
                    workspace: acme
                    name: pii-key
                  - key_hash: sha256:%s
                    workspace: acme
                    name: burst-key
                  - key_hash: sha256:%s
                    workspace: acme
                    name: prompt-key
                  - key_hash: sha256:%s
                    workspace: tight
                    name: tight-key
                  - key_hash: sha256:%s
                    workspace: redactor
                    name: redact-key

                routes:
                  - id: mock-route
                    model: "mock*"
                    provider: mock

                prompt_templates:
                  - id: support-reply
                    workspace: acme
                    template: "Answer politely: {{question}}"

                policies:
                  - id: no-forbidden-model
                    workspace: acme
                    status: ACTIVE
                    dsl: |
                      version: "1"
                      rules:
                        - id: deny-forbidden
                          conditions:
                            model:
                              denylist: [mock/forbidden]
                          action: DENY
                          deny_message: "This model is denied by gateway.yaml"
                """.formatted(h(SERVE_KEY), h(POLICY_KEY), h(PII_KEY), h(BURST_KEY), h(PROMPT_KEY), h(TIGHT_KEY),
                        h(REDACT_KEY)));

        System.setProperty("DVARA_CONFIG_FILE", file.toString());
    }

    @AfterAll
    static void clearConfigPath() {
        // Surefire reuses the JVM across classes; a stray DVARA_CONFIG_FILE would point every later
        // test at a temp file that no longer exists.
        System.clearProperty("DVARA_CONFIG_FILE");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // The audit writer is configured inside the context, so a dynamic property source is early
        // enough for it.
        registry.add("dvara.audit.file.path", () -> configDir.resolve("audit.log").toString());
        registry.add("dvara.audit.hmac-secret", () -> AUDIT_SECRET);
        // REDACT mints a token, and minting needs a secret; there is no default.
        registry.add("dvara.pii.token-encryption-master-password", () -> "open-build-test-pii-secret");
        // A matcher rather than the global mock response, so other tests keep the default text. It
        // echoes the messages as the provider received them, which is what the redaction test reads.
        registry.add("dvara.llm-gateway.providers.mock.latency-ms", () -> "0");
        registry.add("dvara.llm-gateway.providers.mock.stream-token-delay-ms", () -> "0");
        registry.add("dvara.llm-gateway.providers.mock.matchers[0].name", () -> "echo-upstream");
        registry.add("dvara.llm-gateway.providers.mock.matchers[0].when",
                () -> "groovy:request.messages.any { m -> m.content.any { it.toString().contains('"
                        + ECHO_MARKER + "') } }");
        registry.add("dvara.llm-gateway.providers.mock.matchers[0].response",
                () -> "groovy:request.messages.collect { m -> "
                        + "m.content.collect { it.toString() }.join(' ') }.join(' | ')");
    }

    static final String AUDIT_SECRET = "open-build-test-audit-secret-for-the-file-chain";

    /** Carried in the prompt so only the redaction test reaches the echoing matcher. */
    static final String ECHO_MARKER = "gate-echo";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    /**
     * Asserts on the absence of a {@code DataSource} bean definition rather than on a successful
     * request: a context can start with a datasource configured and unused.
     */
    @Test
    void theContextStartsWithNoDataSourceAtAll() {
        assertThat(context.getBeanNamesForType(DataSource.class))
                .as("the application serves with no database, not with an unused one")
                .isEmpty();
        assertThat(context.getBean(com.dvarahq.core.routing.RouteRepository.class))
                .as("the configuration comes from the file")
                .isInstanceOf(com.dvarahq.autoconfigure.config.yaml.repo.YamlRouteRepository.class);
    }

    @Test
    void theProviderDeclaredInTheFileIsRegistered() throws Exception {
        mockMvc.perform(get("/v1/models").header("Authorization", "Bearer " + SERVE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    void aKeyFromTheFileIsServed() throws Exception {
        mockMvc.perform(chat("mock/gpt", "Hello", SERVE_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").exists());
    }

    /** The control for {@link #aKeyFromTheFileIsServed()}: a store that is never consulted would serve every key. */
    @Test
    void aKeyNotInTheFileIsRefused() throws Exception {
        mockMvc.perform(chat("mock/gpt", "Hello", "not-a-key-in-the-file"))
                .andExpect(status().isUnauthorized());
    }

    /** A policy from the file is evaluated, with no database. */
    @Test
    void aPolicyInTheFileDeniesTheRequest() throws Exception {
        mockMvc.perform(chat("mock/forbidden", "Hello", POLICY_KEY))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("denied by gateway.yaml")));
    }

    /**
     * The PII action is read from {@code Workspace.metadata}. The typed settings repositories have
     * no file-backed implementation, so the resolver falls through to the metadata map; that is
     * what lets a workspace be governed from the file.
     */
    @Test
    void aPiiActionFromWorkspaceMetadataBlocksTheRequest() throws Exception {
        mockMvc.perform(chat("mock/gpt", "My social security number is 123-45-6789", PII_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("pii_violation"));
    }

    /**
     * Redaction replaces the value before the request leaves the gateway. The mock echoes the
     * messages as it received them, so the response body is a transcript of what was sent upstream.
     */
    @Test
    void aRedactingWorkspaceSendsAPlaceholderUpstreamAndNeverTheValue() throws Exception {
        String secret = "123-45-6789";

        String upstream = mockMvc.perform(
                        chat("mock/gpt", ECHO_MARKER + " my social security number is " + secret,
                                REDACT_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(upstream)
                .as("the provider never receives the value the workspace redacts")
                .doesNotContain(secret);
        assertThat(upstream)
                .as("the placeholder stands in for the value; an unmatched echo would contain neither "
                        + "and pass doesNotContain alone")
                .contains("[REDACTED_SSN]");
    }

    /**
     * With the file's limit of three requests a minute, the fourth request on a key is refused.
     * {@code GatewayConfigEnvironmentPostProcessor} turns {@code rate_limits} into
     * {@code dvara.llm-gateway.rate-limit.*} properties before the context starts, and
     * {@code InProcessRateLimiter} enforces them.
     */
    @Test
    void theRateLimitFromTheFileIsEnforced() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(chat("mock/gpt", "burst " + i, BURST_KEY))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(chat("mock/gpt", "burst 4", BURST_KEY))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * The {@code tight} workspace's metadata allows one request a minute where the file's default
     * is three, so its second request is refused. {@code MetadataWorkspaceRateLimitResolver} reads
     * {@code rate-limit.requests-per-minute} from the workspace's metadata.
     */
    @Test
    void aWorkspacesOwnRateLimitGovernsItsKeys() throws Exception {
        mockMvc.perform(chat("mock/gpt", "tight 1", TIGHT_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(chat("mock/gpt", "tight 2", TIGHT_KEY))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * A policy denial is written to the audit file on local disk, and the chain verifies. The
     * request is a denial rather than a success because the governance decision is the record that
     * matters.
     */
    @Test
    void aGovernanceDecisionIsRecordedInAVerifiableChain() throws Exception {
        mockMvc.perform(chat("mock/forbidden", "audit me", POLICY_KEY))
                .andExpect(status().isForbidden());

        Path log = configDir.resolve("audit.log");
        assertThat(log).as("the audit log exists once a request has been governed").exists();
        assertThat(java.nio.file.Files.readString(log))
                .as("the denial itself is in the record")
                .contains("POLICY_DENIED");

        var result = com.dvarahq.autoconfigure.audit.FileAuditChainVerifier.verify(log, AUDIT_SECRET);
        assertThat(result.valid()).as(result::describe).isTrue();
        assertThat(result.linesChecked())
                .as("a verdict over zero lines is not a chain")
                .isGreaterThan(0);
    }

    /**
     * The second call names the same template with no variables, and the refusal names
     * {@code question}, a variable that exists only because the file's template text contains
     * {@code {{question}}}.
     */
    @Test
    void aPromptTemplateFromTheFileIsResolvedOnARequest() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + PROMPT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"mock/gpt","messages":[{"role":"user","content":"ignored"}],
                                 "metadata":{"prompt_template_id":"support-reply",
                                             "prompt_variables":{"question":"how do I reset my password"}}}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + PROMPT_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"mock/gpt","messages":[{"role":"user","content":"ignored"}],
                                 "metadata":{"prompt_template_id":"support-reply"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("question")));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder chat(
            String model, String message, String apiKey) {
        return post("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"model":"%s","messages":[{"role":"user","content":"%s"}]}
                        """.formatted(model, message));
    }

    /** The file carries the key's hash; the caller sends the key itself. */
    private static String h(String plaintextKey) {
        return com.dvarahq.core.apikey.ApiKeyGenerator.hash(plaintextKey);
    }
}
