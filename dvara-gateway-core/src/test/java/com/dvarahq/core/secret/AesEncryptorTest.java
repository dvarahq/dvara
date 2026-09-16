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
package com.dvarahq.core.secret;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesEncryptorTest {

    @Test
    void encryptDecrypt_roundTrip() {
        String plaintext = "sk-super-secret-api-key-12345";
        String password = "master-password";

        String encrypted = AesEncryptor.encrypt(plaintext, password);
        String decrypted = AesEncryptor.decrypt(encrypted, password);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime() {
        String plaintext = "same-value";
        String password = "same-password";

        String encrypted1 = AesEncryptor.encrypt(plaintext, password);
        String encrypted2 = AesEncryptor.encrypt(plaintext, password);

        assertThat(encrypted1).isNotEqualTo(encrypted2);

        // Both still decrypt to the same value
        assertThat(AesEncryptor.decrypt(encrypted1, password)).isEqualTo(plaintext);
        assertThat(AesEncryptor.decrypt(encrypted2, password)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_wrongPassword_throws() {
        String encrypted = AesEncryptor.encrypt("secret", "correct-password");

        assertThatThrownBy(() -> AesEncryptor.decrypt(encrypted, "wrong-password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Decryption failed");
    }

    @Test
    void encryptDecrypt_emptyString() {
        String encrypted = AesEncryptor.encrypt("", "password");
        String decrypted = AesEncryptor.decrypt(encrypted, "password");

        assertThat(decrypted).isEmpty();
    }

    @Test
    void encryptDecrypt_unicodeContent() {
        String plaintext = "密码测试-🔐-Ñoño";
        String password = "unicode-password-日本語";

        String encrypted = AesEncryptor.encrypt(plaintext, password);
        String decrypted = AesEncryptor.decrypt(encrypted, password);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void decrypt_invalidBase64_throws() {
        assertThatThrownBy(() -> AesEncryptor.decrypt("not-valid-base64!!!", "password"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decrypt_tooShortData_throws() {
        // Base64 encoding of a very short byte array
        String tooShort = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        assertThatThrownBy(() -> AesEncryptor.decrypt(tooShort, "password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }
}