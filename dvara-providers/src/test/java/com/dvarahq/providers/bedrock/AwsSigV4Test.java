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
package com.dvarahq.providers.bedrock;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public SigV4 signer the Bedrock adapter uses, checked against AWS's documented steps. */
class AwsSigV4Test {

    private static final String ACCESS = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final Instant NOW = Instant.parse("2026-11-28T10:15:30Z");

    /** AWS's published example of a derived signing key (secret above, 20120215, us-east-1, iam). */
    @Test
    void theSigningKeyMatchesAwsPublishedExample() {
        assertThat(HexFormat.of().formatHex(AwsSigV4.signingKey(SECRET, "20120215", "us-east-1", "iam")))
                .isEqualTo("f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d");
    }

    @Test
    void aPostWithABodyIsSignedWithItsContentType_asTheAdapterSendsIt() throws Exception {
        URI uri = URI.create("https://bedrock-runtime.eu-west-1.amazonaws.com/model/anthropic.claude/converse");
        byte[] body = "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

        AwsSigV4.Signed signed = AwsSigV4.sign("POST", uri, "application/json", body, ACCESS, SECRET, "eu-west-1",
                "bedrock", NOW);

        String hash = sha256(body);
        String canonical = "POST\n/model/anthropic.claude/converse\n\ncontent-type:application/json\n"
                + "host:bedrock-runtime.eu-west-1.amazonaws.com\nx-amz-content-sha256:" + hash
                + "\nx-amz-date:20261128T101530Z\n\ncontent-type;host;x-amz-content-sha256;x-amz-date\n" + hash;
        assertThat(signed.amzDate()).isEqualTo("20261128T101530Z");
        assertThat(signed.contentSha256()).isEqualTo(hash);
        assertThat(signed.authorization()).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261128/eu-west-1/"
                + "bedrock/aws4_request, SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature="
                + expectedSignature(canonical, "eu-west-1", "bedrock"));
    }

    @Test
    void aGetWithNoContentTypeSignsTheOtherThreeHeaders() throws Exception {
        URI uri = URI.create("https://bedrock.us-east-1.amazonaws.com/foundation-models");

        AwsSigV4.Signed signed = AwsSigV4.sign("GET", uri, null, null, ACCESS, SECRET, "us-east-1", "bedrock", NOW);

        String hash = sha256(new byte[0]);
        String canonical = "GET\n/foundation-models\n\nhost:bedrock.us-east-1.amazonaws.com\nx-amz-content-sha256:"
                + hash + "\nx-amz-date:20261128T101530Z\n\nhost;x-amz-content-sha256;x-amz-date\n" + hash;
        assertThat(signed.authorization()).endsWith("SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature="
                + expectedSignature(canonical, "us-east-1", "bedrock"));
    }

    @Test
    void aQueryOrAMissingKeyIsRefused() {
        assertThatThrownBy(() -> AwsSigV4.sign("GET", URI.create("https://bedrock.us-east-1.amazonaws.com/x?a=1"),
                null, null, ACCESS, SECRET, "us-east-1", "bedrock", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AwsSigV4.sign("GET", URI.create("https://bedrock.us-east-1.amazonaws.com/x"),
                null, null, ACCESS, " ", "us-east-1", "bedrock", NOW)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void itsTextShowsNoHeaderValue() {
        AwsSigV4.Signed signed = AwsSigV4.sign("GET", URI.create("https://bedrock.us-east-1.amazonaws.com/x"), null,
                null, ACCESS, SECRET, "us-east-1", "bedrock", NOW);

        assertThat(signed.toString()).doesNotContain("Signature", ACCESS);
    }

    /** The signature worked out step by step here, as AWS documents it, and not by the signer under test. */
    private static String expectedSignature(String canonical, String region, String service) throws Exception {
        String toSign = "AWS4-HMAC-SHA256\n20261128T101530Z\n20261128/" + region + "/" + service + "/aws4_request\n"
                + sha256(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8), "20261128");
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        return HexFormat.of().formatHex(hmac(key, toSign));
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
