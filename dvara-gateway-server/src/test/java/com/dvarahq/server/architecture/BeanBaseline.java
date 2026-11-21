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
package com.dvarahq.server.architecture;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every bean an application context starts, as {@code name | type}, compared with a checked-in list.
 *
 * <p>The list is the contract a host application relies on: moving classes between modules, or moving an
 * auto-configuration into a module of its own, must not add or drop a bean. A bean that appears or goes fails the
 * check until the list in {@code src/test/resources/beans/} says so in the same change. On a mismatch the current
 * list is written to {@code target/<name>.actual.txt}; copy it over the baseline only when the change means it.
 *
 * <p>The type is the bean's user class (a CGLIB proxy is named by the class it extends). A lambda's class name
 * changes from run to run, so a lambda bean is named by its {@code @Bean} method's return type instead.
 */
final class BeanBaseline {

    private BeanBaseline() {
    }

    static void assertMatches(ConfigurableListableBeanFactory factory, String name) throws IOException {
        Path baseline = Path.of("src", "test", "resources", "beans", name + ".txt");
        Path actualFile = Path.of("target", name + ".actual.txt");
        Set<String> actual = beans(factory);
        Set<String> expected = Files.exists(baseline) ? read(baseline) : Set.of();
        if (!actual.equals(expected)) {
            Files.createDirectories(actualFile.getParent());
            Files.writeString(actualFile, String.join("\n", actual) + "\n");
        }
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(expected);
        Set<String> removed = new TreeSet<>(expected);
        removed.removeAll(actual);
        assertThat(added)
                .as("Beans the context starts that %s does not list. If the change means them, copy %s over it.",
                        baseline, actualFile)
                .isEmpty();
        assertThat(removed)
                .as("Beans %s lists that the context no longer starts. If the change means it, copy %s over it.",
                        baseline, actualFile)
                .isEmpty();
    }

    /** Each bean as {@code name | type}, sorted. */
    static Set<String> beans(ConfigurableListableBeanFactory factory) {
        Set<String> beans = new TreeSet<>();
        for (String name : factory.getBeanDefinitionNames()) {
            beans.add(name + " | " + typeOf(factory, name));
        }
        return beans;
    }

    private static String typeOf(ConfigurableListableBeanFactory factory, String name) {
        Class<?> type = factory.getType(name, false);
        if (type == null) {
            return "?";
        }
        if (type.isSynthetic() && type.getName().contains("$$Lambda")) {
            BeanDefinition definition = factory.getMergedBeanDefinition(name);
            if (definition instanceof RootBeanDefinition root && root.getResolvedFactoryMethod() != null) {
                return root.getResolvedFactoryMethod().getReturnType().getName();
            }
            return type.getName().substring(0, type.getName().indexOf("$$Lambda")) + "$$Lambda";
        }
        return ClassUtils.getUserClass(type).getName();
    }

    private static Set<String> read(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
