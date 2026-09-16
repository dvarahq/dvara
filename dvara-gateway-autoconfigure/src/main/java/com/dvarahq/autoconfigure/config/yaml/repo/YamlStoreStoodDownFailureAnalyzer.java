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

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Says in words why a repository is missing when it is missing because the YAML store stood down.
 *
 * <p>{@link YamlConfigStoreAutoConfiguration} registers only when no {@code JdbcClient} is on the
 * classpath, so that a forgotten datasource cannot silently move where configuration comes from.
 * The cost is the failure mode when an application embedding the gateway adds {@code spring-jdbc}
 * for its own tables: the store stands down, nothing else provides the repositories, and the
 * context dies on the first consumer with Spring's generic <em>required a bean of type
 * WorkspaceRepository that could not be found</em>. Nothing names {@code gateway.yaml} or
 * {@code JdbcClient}. This analyzer does.
 *
 * <p>It recognises the repository types the store would have provided, read off the store's own
 * bean methods, and speaks only when {@code JdbcClient} is present and the pod is not serving from
 * a signed bundle. Ordered first so it wins over Boot's generic missing-bean analyzer for these
 * types.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class YamlStoreStoodDownFailureAnalyzer extends AbstractFailureAnalyzer<NoSuchBeanDefinitionException> {

    static final String JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient";
    static final String SERVE_FROM_BUNDLE = "dvara.data-plane.serve-from-bundle";

    /**
     * The repository types the store would have provided — its {@code @Bean} methods returning a
     * {@code …Repository}, read off the class itself so the list cannot drift. Only those: the
     * store also registers a route publisher and two metrics collectors, and "supply the
     * repositories" is not the remedy for a missing one of those.
     */
    static final Set<Class<?>> REPOSITORIES_PROVIDED_BY_THE_STORE = Arrays.stream(
                    YamlConfigStoreAutoConfiguration.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Bean.class))
            .map(Method::getReturnType)
            .filter(t -> t.getSimpleName().endsWith("Repository"))
            .collect(Collectors.toUnmodifiableSet());

    private final Environment environment;

    /**
     * The one constructor, deliberately: Boot instantiates analyzers through
     * {@code SpringFactoriesLoader}, which resolves an {@code Environment} argument and otherwise
     * needs a single constructor to choose.
     */
    public YamlStoreStoodDownFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    /** Overridable for tests; the real answer is the classpath. */
    protected boolean jdbcClientPresent() {
        return ClassUtils.isPresent(JDBC_CLIENT, YamlStoreStoodDownFailureAnalyzer.class.getClassLoader());
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause) {
        Class<?> missing = cause.getResolvableType() != null ? cause.getResolvableType().resolve() : null;
        if (missing == null || !REPOSITORIES_PROVIDED_BY_THE_STORE.contains(missing) || !jdbcClientPresent()) {
            return null;
        }
        if (environment.getProperty(SERVE_FROM_BUNDLE, Boolean.class, false)) {
            // The store also stands down for a bundle-serving pod, and there JdbcClient is not the
            // reason and removing spring-jdbc is not the remedy; leave that failure to Boot's own
            // analyzer rather than send the operator after the wrong cause.
            return null;
        }
        String description = "No bean of type " + missing.getSimpleName() + " is available, because gateway.yaml "
                + "is not being read: the file-backed configuration store registers only when "
                + JDBC_CLIENT + " is absent from the classpath, and it is present. That condition is "
                + "deliberate — with a database possible, a forgotten datasource must not silently decide "
                + "where configuration comes from — so the store stood down, and nothing else on this "
                + "classpath provides " + missing.getSimpleName() + ".";
        String action = "Either remove spring-jdbc (and anything that brings it) from the application so "
                + "gateway.yaml is read, or register your own configuration repositories: implementations "
                + "of the com.dvarahq.core repository interfaces.";
        return new FailureAnalysis(description, action, cause);
    }
}
