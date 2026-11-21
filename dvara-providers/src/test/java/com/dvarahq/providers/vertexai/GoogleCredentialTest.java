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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Reading the credential files Google writes, and refusing the ones the gateway cannot use. */
class GoogleCredentialTest {

    static final TestKeys KEYS = new TestKeys();

    @Test
    void aServiceAccountKeyIsRead_andPrintsOnlyItsIdentity() {
        GoogleCredential credential = GoogleCredential.parse(KEYS.serviceAccountJson());

        assertThat(credential).isInstanceOfSatisfying(GoogleCredential.ServiceAccount.class, sa -> {
            assertThat(sa.clientEmail()).isEqualTo(TestKeys.CLIENT_EMAIL);
            assertThat(sa.privateKeyId()).isEqualTo(TestKeys.KEY_ID);
            assertThat(sa.privateKey().getEncoded()).isEqualTo(KEYS.pair.getPrivate().getEncoded());
        });
        assertThat(credential.toString()).isEqualTo("service_account:" + TestKeys.CLIENT_EMAIL + ":" + TestKeys.KEY_ID)
                .doesNotContain("PRIVATE KEY");
    }

    @Test
    void gcloudsApplicationDefaultCredentialsAreRead_andPrintNoSecret() {
        GoogleCredential credential = GoogleCredential.parse("""
                {"type": "authorized_user", "client_id": "cid.apps.googleusercontent.com",
                 "client_secret": "the-client-secret", "refresh_token": "1//the-refresh-token",
                 "quota_project_id": "my-project"}
                """);

        assertThat(credential).isEqualTo(new GoogleCredential.AuthorizedUser("cid.apps.googleusercontent.com",
                "the-client-secret", "1//the-refresh-token"));
        assertThat(credential.toString()).doesNotContain("the-client-secret").doesNotContain("the-refresh-token");
    }

    @Test
    void federationAndImpersonationAreRefusedByName() {
        assertThatIllegalArgumentException().isThrownBy(() -> GoogleCredential.parse("{\"type\":\"external_account\"}"))
                .withMessageContaining("\"external_account\" are not supported");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GoogleCredential.parse("{\"type\":\"impersonated_service_account\"}"))
                .withMessageContaining("impersonated_service_account");
    }

    @Test
    void aMissingFieldIsNamed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> GoogleCredential.parse("{\"type\":\"service_account\",\"client_email\":\"a@b\"}"))
                .withMessage("The Google credential of type \"service_account\" has no \"private_key\"");
        assertThatIllegalArgumentException().isThrownBy(() -> GoogleCredential.parse("{}"))
                .withMessage("The Google credential has no \"type\"");
    }

    @Test
    void aBrokenKeyOrFileIsRefused_withoutEchoingIt() {
        String broken = "{\"type\":\"service_account\",\"client_email\":\"a@b\",\"private_key\":\"not-a-key-abc123\"}";
        assertThatIllegalArgumentException().isThrownBy(() -> GoogleCredential.parse(broken))
                .withMessage("The service account's private_key is not a PKCS#8 RSA key");
        assertThatIllegalArgumentException().isThrownBy(() -> GoogleCredential.parse("{\"private_key\": \"abc"))
                .withMessage("The Google credential is not valid JSON");
    }
}
