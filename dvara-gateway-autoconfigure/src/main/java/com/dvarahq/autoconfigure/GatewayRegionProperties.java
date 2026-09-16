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
package com.dvarahq.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-cutting region identity. Every app that reads this configuration runs in exactly one
 * geographic region, and the values flow into routing decisions (data residency, region-aware
 * policy), the {@code /actuator/gateway-status} payload and per-region telemetry labels.
 *
 * <p>A standalone {@code @ConfigurationProperties} rather than a block inside
 * {@link GatewayProperties}, so the prefix stays {@code dvara.region.*} and is not tied to the
 * LLM-gateway-only {@code dvara.llm-gateway.*} namespace.
 *
 * <p>Env: {@code DVARA_REGION_ID}, {@code DVARA_REGION_NAME}.
 */
@Data
@ConfigurationProperties("dvara.region")
public class GatewayRegionProperties {

    /**
     * Region identifier (e.g. {@code us-east-1}, {@code eu-west-1}). Blank
     * means region-unaware; routing strategies that depend on region
     * (data-residency, geo-aware) treat blank as a wildcard.
     */
    private String id;

    /** Human-readable region name (e.g. {@code US East}). */
    private String name;
}