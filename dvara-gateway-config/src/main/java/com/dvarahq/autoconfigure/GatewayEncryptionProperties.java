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
 * Cross-cutting encryption configuration, read by every app that needs to decrypt an
 * {@code ENC:}-prefixed secret or a database-stored credential.
 *
 * <p>A standalone {@code @ConfigurationProperties} rather than a block inside
 * {@link GatewayProperties}, so the prefix stays {@code dvara.encryption.*} and is not tied to the
 * LLM-gateway-only {@code dvara.llm-gateway.*} namespace.
 *
 * <p>Env: {@code DVARA_ENCRYPTION_MASTER_PASSWORD}.
 */
@Data
@ConfigurationProperties("dvara.encryption")
public class GatewayEncryptionProperties {

    /**
     * AES-256-GCM key that encrypts ENCRYPTED-mode provider credentials at rest. Required when
     * credentials are created in ENCRYPTED storage mode (the default); optional for REFERENCE-only
     * deployments. Keep a copy offline: losing it makes every encrypted credential unrecoverable.
     */
    private String masterPassword;
}