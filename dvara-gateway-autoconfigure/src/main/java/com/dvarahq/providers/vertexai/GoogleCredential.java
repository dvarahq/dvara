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

import com.dvarahq.core.util.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * A Google credential the gateway can turn into an OAuth2 access token. Three kinds:
 * <ul>
 *   <li>a service account key ({@code "type": "service_account"}), which signs its own token request;</li>
 *   <li>a user's application default credentials from {@code gcloud auth application-default login}
 *       ({@code "type": "authorized_user"}), which trades a refresh token;</li>
 *   <li>the metadata server of the machine the gateway runs on, which is how a GKE pod with workload identity, a
 *       Cloud Run service or a Compute Engine VM gets its token. It has no file.</li>
 * </ul>
 *
 * <p>No method of a credential returns or prints its secret parts: {@code toString} names the identity only.
 */
sealed interface GoogleCredential {

    /** Who the credential acts as, without any secret, for logs and the credential fingerprint. */
    String identity();

    /** A service account key. Its private key signs the token request; the key's own token URL is not used. */
    record ServiceAccount(String clientEmail, String privateKeyId, PrivateKey privateKey) implements GoogleCredential {
        @Override
        public String identity() {
            return "service_account:" + clientEmail + (privateKeyId == null ? "" : ":" + privateKeyId);
        }

        @Override
        public String toString() {
            return identity();
        }
    }

    /** A user's credential, for a gateway run on a developer's machine. */
    record AuthorizedUser(String clientId, String clientSecret, String refreshToken) implements GoogleCredential {
        @Override
        public String identity() {
            return "authorized_user:" + clientId;
        }

        @Override
        public String toString() {
            return identity();
        }
    }

    /** The token of the service account attached to the machine, read from its metadata server. */
    record MetadataServer(String baseUrl) implements GoogleCredential {
        @Override
        public String identity() {
            return "metadata_server";
        }
    }

    /**
     * Reads a credential JSON file's content. Refuses a kind the gateway cannot use, with a message that names the
     * kind and never the content.
     *
     * @throws IllegalArgumentException when the content is not a usable credential
     */
    static GoogleCredential parse(String json) {
        JsonNode node;
        try {
            node = JsonMapper.instance().readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("The Google credential is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("The Google credential is not a JSON object");
        }
        String type = text(node, "type");
        if ("service_account".equals(type)) {
            return new ServiceAccount(required(node, "client_email", type), text(node, "private_key_id"),
                    privateKey(required(node, "private_key", type)));
        }
        if ("authorized_user".equals(type)) {
            return new AuthorizedUser(required(node, "client_id", type), required(node, "client_secret", type),
                    required(node, "refresh_token", type));
        }
        if (type == null) {
            throw new IllegalArgumentException("The Google credential has no \"type\"");
        }
        // external_account (workload identity federation) and impersonated_service_account need a token exchange
        // with another service first. On GKE, workload identity is read from the metadata server instead.
        throw new IllegalArgumentException("Google credentials of type \"" + type + "\" are not supported; use a "
                + "service account key, gcloud application default credentials, or the metadata server");
    }

    /** Reads a PKCS#8 PEM RSA key, the form Google writes in a service account key file. */
    static PrivateKey privateKey(String pem) {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException("The service account's private_key is not a PKCS#8 RSA key");
        }
    }

    private static String required(JsonNode node, String field, String type) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("The Google credential of type \"" + type + "\" has no \"" + field + "\"");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }
}
