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

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** A service account key made up for a test, in the shape Google writes one. Nothing here is a real credential. */
final class TestKeys {

    static final String CLIENT_EMAIL = "gateway@test-project.iam.gserviceaccount.com";
    static final String KEY_ID = "test-key-id-1";

    final KeyPair pair;

    TestKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            pair = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String pem() {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /** A service account key file's content. Its token_uri points nowhere useful, so a test shows it is not used. */
    String serviceAccountJson() {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("type", "service_account");
        key.put("project_id", "test-project");
        key.put("private_key_id", KEY_ID);
        key.put("private_key", pem());
        key.put("client_email", CLIENT_EMAIL);
        key.put("client_id", "1234567890");
        key.put("token_uri", "https://token-uri-from-the-file.invalid/token");
        try {
            return JsonMapper.instance().writeValueAsString(key);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    GoogleCredential.ServiceAccount serviceAccount() {
        return (GoogleCredential.ServiceAccount) GoogleCredential.parse(serviceAccountJson());
    }

    /** Whether the JWT's signature is this key's, by the JDK. */
    boolean signed(String jwt) {
        String[] parts = jwt.split("\\.");
        try {
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initVerify(pair.getPublic());
            rs256.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return rs256.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }

    static Map<?, ?> part(String jwt, int index) {
        try {
            return JsonMapper.instance().readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
