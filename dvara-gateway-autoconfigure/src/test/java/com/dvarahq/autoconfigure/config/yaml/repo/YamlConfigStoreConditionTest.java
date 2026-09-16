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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The YAML store's classpath condition, in both directions, and the failure analyzer that explains
 * the stand-down.
 */
class YamlConfigStoreConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(YamlConfigStoreAutoConfiguration.class))
            // the store's route publisher needs an engine; the routing auto-configuration is not under test here
            .withBean(com.dvarahq.core.routing.RoutingEngine.class,
                    () -> new com.dvarahq.core.routing.RoutingEngine((req, providers) -> providers.get(0), java.util.List.of()))
            .withBean(com.dvarahq.core.routing.RoutingStrategyFactory.class,
                    com.dvarahq.autoconfigure.routing.DefaultRoutingStrategyFactory::new);

    @Test
    void withoutJdbcClient_theStoreRegisters() {
        runner.withClassLoader(new FilteredClassLoader(JdbcClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("yamlWorkspaceRepository");
            assertThat(context).hasSingleBean(WorkspaceRepository.class);
        });
    }

    /** spring-jdbc is on this test classpath, so this is the embedder-adds-spring-jdbc case. */
    @Test
    void withJdbcClient_theStoreStandsDown() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("yamlWorkspaceRepository");
            assertThat(context).doesNotHaveBean(WorkspaceRepository.class);
        });
    }

    /** The embedder's consumer then fails — and the analyzer says why, naming the file and the class. */
    @Test
    void aConsumerFailingForThatReason_isExplained() {
        runner.withUserConfiguration(NeedsWorkspaces.class).run(context -> {
            assertThat(context).hasFailed();
            FailureAnalysis analysis = analyzer(context).analyze(context.getStartupFailure());
            assertThat(analysis).as("the analyzer recognises the failure").isNotNull();
            assertThat(analysis.getDescription()).contains("gateway.yaml").contains("JdbcClient").contains("WorkspaceRepository");
            assertThat(analysis.getAction()).contains("spring-jdbc").contains("repositories");
        });
    }

    @Test
    void aMissingBeanOfAnotherType_isNotExplainedByIt() {
        runner.withUserConfiguration(NeedsSomethingElse.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(analyzer(context).analyze(context.getStartupFailure())).isNull();
        });
    }

    /** Without JdbcClient the store's absence is not the explanation, whatever type is missing. */
    @Test
    void withoutJdbcClient_theAnalyzerStaysSilent() {
        NoSuchBeanDefinitionException missing = new NoSuchBeanDefinitionException(WorkspaceRepository.class);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        assertThat(new YamlStoreStoodDownFailureAnalyzer(env) {
            @Override protected boolean jdbcClientPresent() { return false; }
        }.analyze(missing)).isNull();
        assertThat(new YamlStoreStoodDownFailureAnalyzer(env).analyze(missing)).isNotNull();
    }

    /**
     * A pod serving from a signed bundle stands the store down for a different reason; there
     * JdbcClient is not the cause and removing spring-jdbc is not the remedy, so the analyzer
     * declines and Boot's own message stands.
     */
    @Test
    void servingFromABundle_theAnalyzerDeclines() {
        NoSuchBeanDefinitionException missing = new NoSuchBeanDefinitionException(WorkspaceRepository.class);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty(YamlStoreStoodDownFailureAnalyzer.SERVE_FROM_BUNDLE, "true");
        assertThat(new YamlStoreStoodDownFailureAnalyzer(env).analyze(missing)).isNull();
    }

    /** Only the repository types: the store's route publisher and metrics collectors are not "supply the repositories". */
    @Test
    void onlyRepositoryTypesAreRecognised() {
        assertThat(YamlStoreStoodDownFailureAnalyzer.REPOSITORIES_PROVIDED_BY_THE_STORE)
                .contains(WorkspaceRepository.class)
                .allMatch(c -> c.getSimpleName().endsWith("Repository"))
                .doesNotContain(YamlConfigStore.class, YamlRoutePublisher.class);
        NoSuchBeanDefinitionException notARepository = new NoSuchBeanDefinitionException(YamlRoutePublisher.class);
        assertThat(new YamlStoreStoodDownFailureAnalyzer(new org.springframework.mock.env.MockEnvironment()).analyze(notARepository)).isNull();
    }

    /** Boot must actually find it, and order it ahead of its own missing-bean analyzer. */
    @Test
    void bootDiscoversItAndOrdersItFirst() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        java.util.List<org.springframework.boot.diagnostics.FailureAnalyzer> analyzers =
                org.springframework.core.io.support.SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                        .load(org.springframework.boot.diagnostics.FailureAnalyzer.class,
                                // the two arguments Boot's FailureAnalyzers resolves for every analyzer
                                org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver
                                        .of(org.springframework.beans.factory.BeanFactory.class,
                                                new org.springframework.beans.factory.support.DefaultListableBeanFactory())
                                        .and(org.springframework.core.env.Environment.class, env),
                                // and, as Boot does, an analyzer whose own dependencies are absent is skipped
                                (type, name, failure) -> { });
        org.springframework.core.annotation.AnnotationAwareOrderComparator.sort(analyzers);
        int ours = -1, boots = -1;
        for (int i = 0; i < analyzers.size(); i++) {
            String name = analyzers.get(i).getClass().getName();
            if (name.equals(YamlStoreStoodDownFailureAnalyzer.class.getName())) ours = i;
            if (name.endsWith("NoSuchBeanDefinitionFailureAnalyzer")) boots = i;
        }
        assertThat(ours).as("registered in spring.factories and instantiable with an Environment").isNotNegative();
        assertThat(boots).as("Boot's own analyzer is on the classpath").isNotNegative();
        assertThat(ours).as("ordered ahead of Boot's, so its analysis wins for these types").isLessThan(boots);
    }

    /**
     * A bootstrap file cannot seed a read-only store, so setting the property on this build is refused
     * at startup with the reason and where the entries belong — never ignored.
     */
    @Test
    void aBootstrapFileOnThisBuild_refusesTheBoot_namingWhereTheEntriesBelong() {
        for (String name : new String[] {"DVARA_BOOTSTRAP_FILE", "GATEWAY_BOOTSTRAP_FILE"}) {
            runner.withClassLoader(new FilteredClassLoader(JdbcClient.class))
                    .withPropertyValues(name + "=/etc/dvara/bootstrap.yaml")
                    .run(context -> {
                        assertThat(context).as(name).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .hasMessageContaining(name + " is set")
                                .hasMessageContaining("read-only")
                                .hasMessageContaining("Put the workspaces, api_keys and routes in that file");
                    });
        }
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    /**
     * The Helm chart points both variables at the one gateway.yaml on every pod. Here that file is
     * already what the store serves, so it is accepted as applied rather than refused. Decided by the
     * filesystem, so a different spelling of the same existing file is accepted too.
     */
    @Test
    void aBootstrapFileThatIsTheConfigFile_isAcceptedAsAlreadyServed() throws java.io.IOException {
        java.nio.file.Path config = dir.resolve("gateway.yaml");
        java.nio.file.Files.writeString(config, "workspaces: []\n");
        java.nio.file.Files.createDirectories(dir.resolve("etc"));
        String otherSpelling = dir.resolve("etc").resolve("..").resolve("gateway.yaml").toString();
        runner.withClassLoader(new FilteredClassLoader(JdbcClient.class))
                .withPropertyValues("DVARA_CONFIG_FILE=" + config, "DVARA_BOOTSTRAP_FILE=" + otherSpelling)
                .run(context -> assertThat(context).hasNotFailed().hasBean("yamlConfigStore"));
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("DVARA_CONFIG_FILE", config.toString())
                .withProperty("DVARA_BOOTSTRAP_FILE", otherSpelling);
        assertThat(BootstrapFileOnReadOnlyStore.check(env)).contains("same file").contains("nothing to seed");
        assertThat(BootstrapFileOnReadOnlyStore.check(new org.springframework.mock.env.MockEnvironment())).isNull();
    }

    /**
     * Two spellings that normalize to the same path but name different files: {@code link/../gateway.yaml}
     * where {@code link} points into another directory. The filesystem resolves {@code link/..} to that
     * directory, so this is a separate bootstrap file and must be refused, not read as already served.
     */
    @Test
    void aLexicallyEqualPathThroughASymlink_isADifferentFile_andIsRefused() throws java.io.IOException {
        java.nio.file.Path config = dir.resolve("gateway.yaml");
        java.nio.file.Files.writeString(config, "workspaces: []\n");
        java.nio.file.Path other = java.nio.file.Files.createDirectories(dir.resolve("other"));
        java.nio.file.Files.writeString(other.resolve("gateway.yaml"), "workspaces: [{id: w, name: w}]\n");
        java.nio.file.Files.createDirectories(other.resolve("deep"));
        java.nio.file.Files.createSymbolicLink(dir.resolve("link"), other.resolve("deep"));
        String viaLink = dir.resolve("link").resolve("..").resolve("gateway.yaml").toString();
        assertThat(java.nio.file.Path.of(viaLink).normalize()).as("the trap: lexically the config file").isEqualTo(config);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("DVARA_CONFIG_FILE", config.toString())
                .withProperty("DVARA_BOOTSTRAP_FILE", viaLink);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BootstrapFileOnReadOnlyStore.check(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot seed anything here");
    }

    /** A file that does not exist cannot be already served, whatever its path says. */
    @Test
    void aMissingBootstrapFile_isRefused_evenAtTheConfigPath() {
        String missing = dir.resolve("absent").resolve("gateway.yaml").toString();
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("DVARA_CONFIG_FILE", missing)
                .withProperty("DVARA_BOOTSTRAP_FILE", missing);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BootstrapFileOnReadOnlyStore.check(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not exist");
    }

    /** The check reads the property, not the file: unset or blank is quiet. */
    @Test
    void noBootstrapFile_theStoreRegisters() {
        runner.withClassLoader(new FilteredClassLoader(JdbcClient.class))
                .withPropertyValues("DVARA_BOOTSTRAP_FILE=")
                .run(context -> assertThat(context).hasNotFailed().hasBean("yamlConfigStore"));
    }

    private static YamlStoreStoodDownFailureAnalyzer analyzer(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        return new YamlStoreStoodDownFailureAnalyzer(new org.springframework.mock.env.MockEnvironment());
    }

    @Configuration(proxyBeanMethods = false)
    static class NeedsWorkspaces {
        NeedsWorkspaces(WorkspaceRepository workspaces) { }
    }

    @Configuration(proxyBeanMethods = false)
    static class NeedsSomethingElse {
        NeedsSomethingElse(java.util.concurrent.ExecutorService executor) { }
    }
}
