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
package com.dvarahq.server.actuator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status payload's shape. The endpoint reports what a gateway is doing, and its keys are
 * asserted by name so that a field nobody reads cannot sit in it unnoticed.
 */
class GatewayStatusInfoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Everything the endpoint can set, so a field that serialises only when populated still shows up. */
    private static GatewayStatusInfo fullyPopulated() {
        return GatewayStatusInfo.builder()
                .status("running")
                .mode("full")
                .version("1.8.0")
                .uptimeSeconds(42)
                .region(GatewayStatusInfo.RegionInfo.builder().id("eu-west-1").name("EU").regionAware(true).build())
                .providers(List.of(GatewayStatusInfo.ProviderInfo.builder()
                        .name("openai").type("OpenAiProvider").health("HEALTHY")
                        .capabilities(GatewayStatusInfo.CapabilitiesInfo.builder().streaming(true).build())
                        .build()))
                .routes(List.of(GatewayStatusInfo.RouteInfo.builder().id("r1").build()))
                .rateLimits(GatewayStatusInfo.RateLimitInfo.builder().enabled(true).build())
                .sections(Map.of("configBundle", Map.of("stale", false)))
                .warnings(List.of("a warning"))
                .build();
    }

    @Test
    void thePayloadCarriesNoLicenceField() throws Exception {
        JsonNode json = MAPPER.valueToTree(fullyPopulated());

        assertThat(json.properties())
                .describedAs("the status payload has no licence-shaped field; nothing here has one to report")
                .noneSatisfy(field -> assertThat(field.getKey().toLowerCase())
                        .containsAnyOf("licen", "posture", "entitle", "tier", "edition"));
    }

    @Test
    void thePayloadCarriesWhatEveryGatewayHas() throws Exception {
        JsonNode json = MAPPER.valueToTree(fullyPopulated());

        assertThat(keysOf(json)).containsExactlyInAnyOrder(
                "status", "mode", "version", "uptimeSeconds",
                "region", "providers", "routes", "rateLimits", "warnings",
                // contributed under their own keys by modules the endpoint does not know
                "configBundle");
    }

    private static java.util.List<String> keysOf(JsonNode json) {
        return json.properties().stream().map(Map.Entry::getKey).toList();
    }

    @Test
    void aContributedSectionIsWrittenUnderItsOwnKey() throws Exception {
        JsonNode json = MAPPER.valueToTree(fullyPopulated());

        assertThat(json.get("configBundle").get("stale").asBoolean()).isFalse();
        assertThat(json.has("sections"))
                .describedAs("sections is the carrier, not a key on the wire")
                .isFalse();
    }
}
