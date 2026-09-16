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

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import org.springframework.core.env.Environment;

/**
 * Judges a bootstrap file on a build that cannot seed. {@code DVARA_BOOTSTRAP_FILE} asks the
 * bootstrap loader to write workspaces, keys and routes into the repositories; on a build configured
 * from {@code gateway.yaml} those repositories are read-only, so nothing could be seeded. The loader
 * is absent on such a build, and this is the other half: the property is never silently ignored.
 *
 * <p>Two outcomes. A bootstrap file that <em>is</em> the configuration file — the Helm chart sets
 * both variables to the one {@code gateway.yaml} for every pod, since a database-backed pod seeds
 * from it and a file-configured pod serves it — is already applied by the store, so it is accepted
 * and said so. Any other file is refused at startup with the reason and where its entries belong.
 */
final class BootstrapFileOnReadOnlyStore {

    static final String CANONICAL = "DVARA_BOOTSTRAP_FILE";
    static final String LEGACY = "GATEWAY_BOOTSTRAP_FILE";

    private BootstrapFileOnReadOnlyStore() {}

    /** The bootstrap variable's name and value, or null when neither is set. */
    static String[] bootstrapFile(Environment environment) {
        for (String name : new String[] {CANONICAL, LEGACY}) {
            String value = environment.getProperty(name);
            if (value != null && !value.isBlank()) return new String[] {name, value};
        }
        return null;
    }

    /**
     * Returns a one-line note when the bootstrap file is the configuration file the store already
     * serves; returns null when no bootstrap file is set; throws for any other file.
     *
     * <p>"The same file" is decided by the filesystem ({@code Files.isSameFile}), never by comparing
     * paths: a lexical {@code ..} across a symbolic link can make two spellings look equal while they
     * name different files, and a file that does not exist cannot be already served — either would
     * quietly turn a separate bootstrap file into "nothing to seed", the outcome this exists to
     * refuse.
     */
    static String check(Environment environment) {
        String[] set = bootstrapFile(environment);
        if (set == null) return null;
        String name = set[0];
        String value = set[1];
        java.nio.file.Path bootstrap = java.nio.file.Path.of(value).toAbsolutePath();
        java.nio.file.Path config = GatewayYamlLoader.resolveConfigPath(environment::getProperty).toAbsolutePath();
        if (isSameExistingFile(bootstrap, config)) {
            return name + " names the same file as the configuration store reads (" + config + "); its "
                    + "workspaces, api_keys and routes are already served from there, so there is nothing to seed.";
        }
        String exists = java.nio.file.Files.exists(bootstrap) ? "" : " (a file that does not exist)";
        throw new IllegalStateException(name + " is set to '" + value + "'" + exists + ", but this build reads its "
                + "configuration from " + config + " and treats it as read-only, so a bootstrap file cannot "
                + "seed anything here. Put the workspaces, api_keys and routes in that file instead and unset "
                + name + ". Seeding from a separate bootstrap file needs a build whose repositories accept "
                + "writes — one with a database.");
    }

    private static boolean isSameExistingFile(java.nio.file.Path a, java.nio.file.Path b) {
        try {
            return java.nio.file.Files.isRegularFile(a) && java.nio.file.Files.isRegularFile(b)
                    && java.nio.file.Files.isSameFile(a, b);
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
