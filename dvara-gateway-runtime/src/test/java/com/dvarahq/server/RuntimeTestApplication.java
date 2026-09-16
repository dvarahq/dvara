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

import org.springframework.boot.autoconfigure.SpringBootApplication;

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
 */
@SpringBootApplication
class RuntimeTestApplication {
}