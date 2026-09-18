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

import com.dvarahq.core.apikey.ApiKeyRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The configuration class this module's slice tests search upward for.
 *
 * <p>{@code @WebMvcTest} and friends locate a {@code @SpringBootConfiguration} by walking up the
 * package tree from the test. The runnable application lives in {@code dvara-gateway-server}, which
 * this library's tests cannot see, so this test-scoped class stands in for it.
 *
 * <p>It is {@code @SpringBootApplication}, component scan included, on purpose: {@code @WebMvcTest}
 * does not add the controller under test as a bean, it narrows the application's existing component
 * scan to web components plus that class. A configuration with no scan gives the slice nothing, and
 * every request answers 404. The production {@link GatewayRuntimeAutoConfiguration} cannot serve
 * here either, because {@code @WebMvcTest} deliberately does not apply it.
 *
 * <p>Every request under {@code /v1} is authenticated before it is served, so the slice needs a
 * key store and a key. {@link #testApiKeys()} is the store, holding {@link TestApiKey} alone, and
 * {@link #authenticatedByDefault()} sends that key on every {@code MockMvc} request, so a slice
 * test reads as a call the gateway would serve rather than one it would refuse. A test about the
 * refusal itself drives the filter directly.
 */
@SpringBootApplication
class RuntimeTestApplication {

    /**
     * Primary, so that in a full-context test — where the {@code gateway.yaml} store registers a
     * repository of its own — the filter still resolves the test key.
     */
    @Bean
    @Primary
    ApiKeyRepository testApiKeys() {
        return TestApiKey.repository();
    }

    @Bean
    MockMvcBuilderCustomizer authenticatedByDefault() {
        return builder -> builder.defaultRequest(get("/").header("Authorization", TestApiKey.BEARER));
    }
}
