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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * AWS Signature Version 4 as the Bedrock adapter signs its calls, public so that other code (a credential check, for
 * one) signs exactly as live calls do.
 *
 * <p>Signed: {@code content-type} when the request has one, {@code host}, {@code x-amz-content-sha256} and
 * {@code x-amz-date}. The payload is always hashed. The canonical path is the URI's path; a request with a query is
 * refused, because the adapter's calls have none and the query is not part of what is signed here.
 *
 * <p>The secret key is used only to derive the signing key. Nothing here logs, and {@link Signed#toString} shows no
 * header value.
 */
public final class AwsSigV4 {

    private static final DateTimeFormatter DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private AwsSigV4() {
    }

    /**
     * The headers a signed request adds.
     *
     * @param amzDate       the {@code x-amz-date} value
     * @param contentSha256 the {@code x-amz-content-sha256} value: the body's SHA-256, in hex
     * @param authorization the {@code Authorization} value
     */
    public record Signed(String amzDate, String contentSha256, String authorization) {
        @Override
        public String toString() {
            return "Signed[amzDate=" + amzDate + "]";
        }
    }

    /**
     * Signs one request.
     *
     * @param method          the HTTP method, such as {@code POST}
     * @param uri             the request's URI; its host is the signed {@code host}
     * @param contentType     the request's {@code Content-Type}, or null when it sends none
     * @param body            the body; null is empty
     * @param accessKeyId     the AWS access key id
     * @param secretAccessKey the AWS secret access key
     * @param region          the AWS region, such as {@code us-east-1}
     * @param service         the signing name, such as {@code bedrock}
     * @param now             the signing time
     * @throws IllegalArgumentException when the URI has a query, or a key, the region or the service is blank
     */
    public static Signed sign(String method, URI uri, String contentType, byte[] body, String accessKeyId,
                              String secretAccessKey, String region, String service, Instant now) {
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("a request with a query is not signed here");
        }
        if (blank(accessKeyId) || blank(secretAccessKey) || blank(region) || blank(service)) {
            throw new IllegalArgumentException("signing needs both keys, a region and a service");
        }
        String dateStamp = DATE_STAMP.format(now);
        String amzDate = AMZ_DATE.format(now);
        String payloadHash = sha256Hex(body == null ? new byte[0] : body);
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();

        StringBuilder canonicalHeaders = new StringBuilder();
        String signedHeaders;
        if (contentType != null) {
            canonicalHeaders.append("content-type:").append(contentType).append('\n');
            signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
        } else {
            signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        }
        canonicalHeaders.append("host:").append(uri.getHost()).append('\n')
                .append("x-amz-content-sha256:").append(payloadHash).append('\n')
                .append("x-amz-date:").append(amzDate).append('\n');

        String canonicalRequest = method + "\n" + path + "\n" + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(
                hmacSha256(signingKey(secretAccessKey, dateStamp, region, service), stringToSign));
        String authorization = ALGORITHM + " Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new Signed(amzDate, payloadHash, authorization);
    }

    /** The derived signing key, as AWS documents it: the date, region, service and {@code aws4_request} in turn. */
    static byte[] signingKey(String secretAccessKey, String dateStamp, String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
