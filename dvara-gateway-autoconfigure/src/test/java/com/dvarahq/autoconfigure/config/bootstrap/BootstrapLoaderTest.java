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
package com.dvarahq.autoconfigure.config.bootstrap;

import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyGenerator;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.apikey.ApiKeyStatus;
import com.dvarahq.core.routing.Route;
import com.dvarahq.core.routing.RouteRepository;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BootstrapLoaderTest {

    @TempDir
    Path tempDir;

    private WorkspaceRepository workspaceRepository;
    private ApiKeyRepository apiKeyRepository;
    private RouteRepository routeRepository;
    private RoutingEngine routingEngine;
    private RoutingStrategyFactory strategyFactory;
    private Environment environment;
    private BootstrapLoader loader;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        apiKeyRepository = mock(ApiKeyRepository.class);
        routeRepository = mock(RouteRepository.class);
        routingEngine = mock(RoutingEngine.class);
        strategyFactory = mock(RoutingStrategyFactory.class);
        environment = mock(Environment.class);

        when(workspaceRepository.findById(any())).thenReturn(Optional.empty());
        when(workspaceRepository.findAll()).thenReturn(new ArrayList<>());
        when(routeRepository.findById(any())).thenReturn(Optional.empty());
        when(routeRepository.findAll()).thenReturn(new ArrayList<>());

        loader = new BootstrapLoader(workspaceRepository, apiKeyRepository,
                routeRepository, routingEngine, strategyFactory, environment);
    }

    @Test
    void skipsWhenEnvVarNotSet() {
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(null);

        loader.run(new DefaultApplicationArguments());

        verifyNoInteractions(workspaceRepository);
    }

    @Test
    void skipsWhenFileNotFound() {
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn("/nonexistent/path.yaml");

        loader.run(new DefaultApplicationArguments());

        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void aWorkspaceIdTheRestOfTheSystemCannotUseIsRefusedWithoutCostingTheOthers() throws IOException {
        // The file's id is stored verbatim, and a workspace id is part of the credential cache key,
        // the guardrail plugin key and the signed audit record. A workspace named after the
        // platform sentinel would occupy the platform-default credential slot. Each bad entry is
        // refused on its own, so the good rows still seed.
        Path file = writeBootstrapFile("""
                workspaces:
                  - id: __platform__
                    name: Impostor
                  - id: acme|evil
                    name: Separator
                  - id: acme
                    name: Acme Corp
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());

        loader.run(new DefaultApplicationArguments());

        ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("acme");
    }

    @Test
    void seedsWorkspaces() throws IOException {
        Path file = writeBootstrapFile("""
                workspaces:
                  - id: acme
                    name: Acme Corp
                    status: active
                  - id: dev
                    name: Dev Team
                    status: suspended
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());

        loader.run(new DefaultApplicationArguments());

        ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceRepository, times(2)).save(captor.capture());
        List<Workspace> workspaces = captor.getAllValues();

        assertThat(workspaces.get(0).getId()).isEqualTo("acme");
        assertThat(workspaces.get(0).getName()).isEqualTo("Acme Corp");
        assertThat(workspaces.get(0).getStatus()).isEqualTo(WorkspaceStatus.ACTIVE);

        assertThat(workspaces.get(1).getId()).isEqualTo("dev");
        assertThat(workspaces.get(1).getStatus()).isEqualTo(WorkspaceStatus.SUSPENDED);
    }

    @Test
    void skipsDuplicateWorkspaces() throws IOException {
        Path file = writeBootstrapFile("""
                workspaces:
                  - id: existing
                    name: Existing
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(workspaceRepository.findById("existing")).thenReturn(
                Optional.of(Workspace.builder().id("existing").name("Existing").build()));

        loader.run(new DefaultApplicationArguments());

        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void seedsApiKeysWithStaticKey() throws IOException {
        Path file = writeBootstrapFile("""
                api_keys:
                  - workspace: production
                    name: prod-key
                    key: gw_test1234567890abcdef1234567890abcdef12345678
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        // resolveWorkspaceId will auto-create the workspace
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        loader.run(new DefaultApplicationArguments());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        ApiKey key = captor.getValue();
        assertThat(key.getName()).isEqualTo("prod-key");
        assertThat(key.getKeyPrefix()).startsWith("gw_");
        assertThat(key.getScopes()).as("omitted scopes mean unrestricted").isEmpty();
    }

    @Test
    void aScopeNoV1EndpointRecognisesIsWarnedAboutWhenTheKeyIsSeeded() throws IOException {
        Path file = writeBootstrapFile("""
                api_keys:
                  - workspace: production
                    name: ci-key
                    key: gw_test1234567890abcdef1234567890abcdef12345678
                    scopes: [chat, completions:write]
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BootstrapLoader.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            loader.run(new DefaultApplicationArguments());
        } finally {
            logger.detachAppender(appender);
        }

        verify(apiKeyRepository).save(any(ApiKey.class));
        List<String> warnings = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("ci-key").contains("'chat'"));
        assertThat(warnings).noneSatisfy(w -> assertThat(w).contains("'completions:write'"));
    }

    @Test
    void seedsApiKeysWithGenerate() throws IOException {
        Path file = writeBootstrapFile("""
                api_keys:
                  - workspace: dev
                    name: dev-key
                    generate: true
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        loader.run(new DefaultApplicationArguments());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        ApiKey key = captor.getValue();
        assertThat(key.getName()).isEqualTo("dev-key");
        assertThat(key.getKeyPrefix()).startsWith("gw_");
        assertThat(key.getKeyHash()).isNotBlank();
    }

    @Test
    void aStaticKeyAlreadyStoredIsNotSavedAgain() throws IOException {
        // The loader runs on every start. Saving the same hash again breaks the unique index and
        // logs a failure on every boot for a key that is fine.
        String plaintext = "gw_test1234567890abcdef1234567890abcdef12345678";
        Path file = writeBootstrapFile("""
                api_keys:
                  - workspace: production
                    name: prod-key
                    key: %s
                """.formatted(plaintext));
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
        when(apiKeyRepository.findByKeyHash(ApiKeyGenerator.hash(plaintext))).thenReturn(Optional.of(
                ApiKey.builder().id("k1").name("prod-key").keyHash(ApiKeyGenerator.hash(plaintext)).build()));

        loader.run(new DefaultApplicationArguments());

        verify(apiKeyRepository, never()).save(any());
    }

    @Test
    void aGeneratedKeyIsNotMintedAgainWhenItsWorkspaceHoldsOneOfThatName() throws IOException {
        // The loader runs on every start, so it must not mint a fresh key each time. A revoked key
        // of that name also counts, so a restart cannot undo a revocation.
        Path file = writeBootstrapFile("""
                api_keys:
                  - workspace: dev
                    name: dev-key
                    generate: true
                  - workspace: dev
                    name: second-key
                    generate: true
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));
        when(apiKeyRepository.findByWorkspaceId(any())).thenReturn(List.of(
                ApiKey.builder().id("k1").name("dev-key").status(ApiKeyStatus.REVOKED).build()));

        loader.run(new DefaultApplicationArguments());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("second-key");
    }

    @Test
    void seedsRoutes() throws IOException {
        Path file = writeBootstrapFile("""
                routes:
                  - id: gpt-route
                    model: "gpt*"
                    provider: openai
                  - id: claude-route
                    model: "claude*"
                    strategy: weighted
                    providers:
                      - provider: anthropic
                        weight: 70
                      - provider: bedrock
                        weight: 30
                    fallback: openai
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());

        loader.run(new DefaultApplicationArguments());

        ArgumentCaptor<Route> captor = ArgumentCaptor.forClass(Route.class);
        verify(routeRepository, times(2)).save(captor.capture());
        List<Route> routes = captor.getAllValues();

        assertThat(routes.get(0).getId()).isEqualTo("gpt-route");
        assertThat(routes.get(0).getModelPattern()).isEqualTo("gpt*");
        assertThat(routes.get(0).getProviders()).hasSize(1);

        assertThat(routes.get(1).getId()).isEqualTo("claude-route");
        assertThat(routes.get(1).getStrategy()).isEqualTo("weighted");
        assertThat(routes.get(1).getProviders()).hasSize(3); // 2 weighted + 1 fallback
    }

    /** An application that stores configuration without serving requests has no routing table. */
    @Test
    void withNoRoutingEngine_routesAreSeededAndNothingIsRouted() throws IOException {
        BootstrapLoader storeOnly = new BootstrapLoader(workspaceRepository, apiKeyRepository,
                routeRepository, null, null, environment);
        Path file = writeBootstrapFile("""
                routes:
                  - id: gpt-route
                    model: "gpt*"
                    provider: openai
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());

        storeOnly.run(new DefaultApplicationArguments());

        verify(routeRepository).save(any(Route.class));
        verify(routeRepository, never()).findAll();
    }

    @Test
    void resolvesEnvVarsInFile() throws IOException {
        Path file = writeBootstrapFile("""
                api_keys:
                  - workspace: prod
                    name: env-key
                    key: ${TEST_BOOTSTRAP_KEY}
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(environment.getProperty("TEST_BOOTSTRAP_KEY")).thenReturn("gw_resolved123456789012345678901234567890ab");
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> inv.getArgument(0));

        loader.run(new DefaultApplicationArguments());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertThat(captor.getValue().getKeyPrefix()).isEqualTo("gw_resolved");
    }

    @Test
    void continuesOnFailure() throws IOException {
        Path file = writeBootstrapFile("""
                workspaces:
                  - id: bad-workspace
                    name: Bad Workspace
                  - id: good-workspace
                    name: Good Workspace
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(workspaceRepository.save(any(Workspace.class)))
                .thenThrow(new RuntimeException("DB error"))
                .thenAnswer(inv -> inv.getArgument(0));

        loader.run(new DefaultApplicationArguments());

        // Both workspaces are attempted; the first failure does not stop the second.
        verify(workspaceRepository, times(2)).save(any());
    }

    @Test
    void seedsFullBootstrapFile() throws IOException {
        Path file = writeBootstrapFile("""
                workspaces:
                  - id: acme
                    name: Acme Corp
                    status: active

                api_keys:
                  - workspace: acme
                    name: prod-key
                    key: gw_abcdef1234567890abcdef1234567890abcdef12

                routes:
                  - id: gpt-route
                    model: "gpt*"
                    provider: openai
                """);
        when(environment.getProperty("GATEWAY_BOOTSTRAP_FILE")).thenReturn(file.toString());
        when(workspaceRepository.save(any(Workspace.class))).thenAnswer(inv -> {
            Workspace t = inv.getArgument(0);
            // After saving, make findById return it for the API key workspace resolution
            when(workspaceRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(workspaceRepository.findAll()).thenReturn(List.of(t));
            return t;
        });

        loader.run(new DefaultApplicationArguments());

        verify(workspaceRepository).save(any(Workspace.class));
        verify(apiKeyRepository).save(any(ApiKey.class));
        verify(routeRepository).save(any(Route.class));
    }

    private Path writeBootstrapFile(String content) throws IOException {
        Path file = tempDir.resolve("bootstrap.yaml");
        Files.writeString(file, content);
        return file;
    }
}
