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
package com.dvarahq.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which envelope a 404 comes back in, over a real socket.
 *
 * <p>{@code GatewayPlane.owns} is a raw prefix test. That is safe only because a non-canonical
 * {@code /v1} path, such as {@code /v%31/...} or one carrying a matrix parameter, is refused with
 * {@code invalid_path} before routing; otherwise an encoded path would reach the 404 handler, resolve
 * to no plane, and get the neutral envelope instead of this plane's.
 *
 * <p>A real container on a real port, deliberately: percent-decoding happens below the application,
 * so MockMvc would answer a different question.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanePathEnvelopeTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String rawPath) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                        // authenticated, so the answer is about the path and not about the missing key
                        .header("Authorization", TestApiKey.BEARER).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUnknownPathUnderThePlanesPrefixGetsThatPlanesEnvelope() throws Exception {
        HttpResponse<String> r = get("/v1/chat/typo");

        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("\"code\":\"resource_not_found\"");
    }

    @Test
    void aPathNoPlaneClaimsGetsTheNeutralEnvelope() throws Exception {
        HttpResponse<String> r = get("/foo");

        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body())
                .as("not resource_not_found: no plane claims /foo, and saying otherwise would tell the "
                        + "caller their request went somewhere it did not")
                .contains("\"code\":\"not_found\"");
    }

    @Test
    void aPercentEncodedPrefixIsRefusedBeforeItCanReachThePlaneMapping() throws Exception {
        // The case a raw prefix test would get wrong; it never reaches the plane mapping.
        HttpResponse<String> r = get("/v%31/chat/typo");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"invalid_path\"");
    }

    @Test
    void aMatrixParameterOnThePrefixIsRefusedTheSameWay() throws Exception {
        HttpResponse<String> r = get("/v1;x=1/chat/typo");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"invalid_path\"");
    }

    @Test
    void theBarePrefixWithNoTrailingSlashBelongsToNoPlane() throws Exception {
        // GatewayPlaneTest checks this as a unit; this is the answer a caller actually receives.
        // It matters for /mcp, where a servlet is mounted at exactly that path.
        HttpResponse<String> r = get("/v1");

        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("\"code\":\"not_found\"");
    }
}
