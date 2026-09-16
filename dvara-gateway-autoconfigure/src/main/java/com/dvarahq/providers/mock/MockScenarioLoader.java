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
package com.dvarahq.providers.mock;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans a directory for {@code *.groovy} scenario files and compiles each into
 * a {@link ScenarioMatcher}. Each scenario file is a plain Groovy script that
 * sets three top-level variables:
 *
 * <pre>
 * // scripts/billing.groovy
 * name = 'billing-question'
 * when = { request -&gt;
 *     request.messages.last().content.any {
 *         it instanceof com.dvarahq.core.model.ContentBlock.TextBlock &amp;&amp;
 *         it.text().toLowerCase().contains('billing')
 *     }
 * }
 * respond = { request -&gt; 'Your balance is $42' }
 * </pre>
 *
 * The loader evaluates the script once at startup. The closures captured in
 * {@code when} and {@code respond} are retained and reused for every incoming
 * request. Evaluation errors (invalid Groovy syntax, missing or wrong-typed
 * variables) are reported with the full file path so operators can find the
 * offending scenario quickly.
 *
 * <p>Files are loaded in lexicographic filename order so users can control
 * matcher precedence by naming files {@code 01-foo.groovy}, {@code 02-bar.groovy},
 * and so on.
 */
public final class MockScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(MockScenarioLoader.class);
    private static final String SCENARIO_EXTENSION = ".groovy";

    private MockScenarioLoader() {}

    /**
     * Scans {@code scenariosDir} and returns a list of compiled scenario
     * matchers in filename order. Returns an empty list if the directory
     * does not exist or contains no {@code .groovy} files.
     *
     * @throws IllegalStateException when any scenario file fails to compile
     *         or is missing the required {@code name} / {@code when} /
     *         {@code respond} bindings. The exception aborts startup so
     *         operators cannot ship a broken scenario.
     */
    public static List<ScenarioMatcher> loadAll(Path scenariosDir) {
        if (scenariosDir == null || !Files.isDirectory(scenariosDir)) {
            log.debug("Mock scenarios directory {} is not a directory — no scenarios loaded", scenariosDir);
            return List.of();
        }

        List<Path> files = listGroovyFiles(scenariosDir);
        if (files.isEmpty()) {
            log.debug("Mock scenarios directory {} contains no .groovy files", scenariosDir);
            return List.of();
        }

        List<ScenarioMatcher> matchers = new ArrayList<>(files.size());
        for (Path file : files) {
            matchers.add(loadOne(file));
        }
        log.info("Loaded {} mock scenario(s) from {}", matchers.size(), scenariosDir);
        return matchers;
    }

    /**
     * Loads a single {@code .groovy} file and returns its compiled matcher.
     *
     * @throws IllegalStateException when the script fails to compile or the
     *         required bindings are missing or wrong-typed
     */
    public static ScenarioMatcher loadOne(Path scenarioFile) {
        String source;
        try {
            source = Files.readString(scenarioFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Mock scenario file " + scenarioFile + " could not be read: " + e.getMessage(), e);
        }

        Binding binding = new Binding();
        try {
            new GroovyShell(binding).evaluate(source, scenarioFile.getFileName().toString());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Mock scenario file " + scenarioFile + " failed to compile: " + e.getMessage(), e);
        }

        String fallbackName = stripExtension(scenarioFile.getFileName().toString());
        String name = extractName(binding, scenarioFile, fallbackName);
        Closure<?> whenClosure = extractClosure(binding, scenarioFile, "when");
        Closure<?> respondClosure = extractClosure(binding, scenarioFile, "respond");

        return new ScenarioMatcher(name, scenarioFile.toString(), whenClosure, respondClosure);
    }

    private static List<Path> listGroovyFiles(Path scenariosDir) {
        try (Stream<Path> stream = Files.list(scenariosDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(SCENARIO_EXTENSION))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to list mock scenarios in " + scenariosDir + ": " + e.getMessage(), e);
        }
    }

    private static String extractName(Binding binding, Path scenarioFile, String fallback) {
        if (!binding.hasVariable("name")) {
            return fallback;
        }
        Object raw = binding.getVariable("name");
        if (raw == null || raw.toString().isBlank()) {
            return fallback;
        }
        if (!(raw instanceof CharSequence)) {
            throw new IllegalStateException(
                    "Mock scenario file " + scenarioFile + ": 'name' must be a String, got "
                            + raw.getClass().getName());
        }
        return raw.toString();
    }

    private static Closure<?> extractClosure(Binding binding, Path scenarioFile, String variableName) {
        if (!binding.hasVariable(variableName)) {
            throw new IllegalStateException(
                    "Mock scenario file " + scenarioFile + " is missing required '" + variableName
                            + "' closure. Every scenario file must define `" + variableName
                            + " = { request -> ... }`.");
        }
        Object raw = binding.getVariable(variableName);
        if (!(raw instanceof Closure<?> closure)) {
            throw new IllegalStateException(
                    "Mock scenario file " + scenarioFile + ": '" + variableName
                            + "' must be a closure of the form `{ request -> ... }`, got "
                            + (raw == null ? "null" : raw.getClass().getName()));
        }
        return closure;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}