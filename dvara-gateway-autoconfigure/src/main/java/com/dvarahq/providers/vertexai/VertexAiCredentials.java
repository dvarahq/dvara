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
package com.dvarahq.providers.vertexai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.secret.SecretProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Which Google credential a Vertex AI call uses, first match wins:
 * <ol>
 *   <li>the secret {@code provider.vertex-ai.credentials}: a key file's JSON content. Read per call through the
 *       secret provider, so a workspace can bring its own and an encrypted value works as for any other key;</li>
 *   <li>the file named by {@code credentials-file};</li>
 *   <li>application default credentials, as Google's own libraries find them: the file named by
 *       {@code GOOGLE_APPLICATION_CREDENTIALS}, else gcloud's {@code application_default_credentials.json}, else
 *       the metadata server (GKE workload identity, Cloud Run, Compute Engine).</li>
 * </ol>
 *
 * <p>A file is read again each time a token is minted, so a key that is rotated in place is picked up at the next
 * refresh without a restart.
 */
final class VertexAiCredentials {

    /** The secret that holds a credential's JSON. */
    static final String SECRET = "provider.vertex-ai.credentials";
    static final String DEFAULT_METADATA_HOST = "metadata.google.internal";

    /** A credential and the key its token is kept under. */
    record Resolved(String cacheKey, Supplier<GoogleCredential> credential) {
    }

    private final SecretProvider secrets;
    private final Path credentialsFile;
    private final Function<String, String> env;
    private final Path userHome;
    /** Application default credentials, found once: the environment does not change while the gateway runs. */
    private volatile Resolved applicationDefault;

    VertexAiCredentials(SecretProvider secrets, String credentialsFile) {
        this(secrets, credentialsFile, System::getenv, Path.of(System.getProperty("user.home", ".")));
    }

    VertexAiCredentials(SecretProvider secrets, String credentialsFile, Function<String, String> env, Path userHome) {
        this.secrets = secrets;
        this.credentialsFile = credentialsFile == null || credentialsFile.isBlank() ? null : Path.of(credentialsFile.trim());
        this.env = env;
        this.userHome = userHome;
    }

    Resolved resolve(String workspaceId) {
        Optional<String> json = secrets == null ? Optional.empty() : secrets.getSecret(SECRET, workspaceId);
        if (json.isPresent() && !json.get().isBlank()) {
            String content = json.get();
            return new Resolved("json:" + sha256(content), () -> parse(content, "the configured credentials"));
        }
        if (credentialsFile != null) {
            return fromFile(credentialsFile);
        }
        Resolved adc = applicationDefault;
        if (adc == null) {
            adc = applicationDefault();
            applicationDefault = adc;
        }
        return adc;
    }

    private Resolved applicationDefault() {
        String named = env.apply("GOOGLE_APPLICATION_CREDENTIALS");
        if (named != null && !named.isBlank()) {
            return fromFile(Path.of(named.trim()));
        }
        Path wellKnown = gcloudConfigDir().resolve("application_default_credentials.json");
        if (Files.isRegularFile(wellKnown)) {
            return fromFile(wellKnown);
        }
        String host = env.apply("GCE_METADATA_HOST");
        String base = "http://" + (host == null || host.isBlank() ? DEFAULT_METADATA_HOST : host.trim());
        GoogleCredential metadata = new GoogleCredential.MetadataServer(base);
        return new Resolved("metadata:" + base, () -> metadata);
    }

    /** Where gcloud keeps its configuration: {@code CLOUDSDK_CONFIG}, else the platform's usual place. */
    private Path gcloudConfigDir() {
        String configured = env.apply("CLOUDSDK_CONFIG");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        String appData = env.apply("APPDATA");
        if (appData != null && !appData.isBlank() && System.getProperty("os.name", "").startsWith("Windows")) {
            return Path.of(appData, "gcloud");
        }
        return userHome.resolve(".config").resolve("gcloud");
    }

    private static Resolved fromFile(Path file) {
        return new Resolved("file:" + file.toAbsolutePath(), () -> {
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new GatewayException("CREDENTIAL_NOT_FOUND",
                        "Vertex AI: cannot read the Google credentials file " + file);
            }
            return parse(content, "the credentials file " + file);
        });
    }

    private static GoogleCredential parse(String json, String where) {
        try {
            return GoogleCredential.parse(json);
        } catch (IllegalArgumentException e) {
            throw new GatewayException("PROVIDER_ERROR", "Vertex AI: " + where + " cannot be used. " + e.getMessage());
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
