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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * The runnable gateway.
 *
 * <p>Not {@code @SpringBootApplication}: that would component-scan this package, and the controllers,
 * dispatcher and filter pipeline already register through {@link GatewayRuntimeAutoConfiguration} in
 * {@code dvara-gateway-runtime}. Scanning as well would define every bean twice, which Spring Boot
 * rejects. The shipped gateway is assembled the same way an embedding application is.
 *
 * <p>This module holds only what must not travel with the runtime: this class, {@code application.yml},
 * {@code banner.txt} and {@code logback-spring.xml}. The last three sit at the classpath root and would
 * replace a host application's own.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class GatewayServerApplication {

    public static void main(String[] args) {
        // Checked before Spring starts: --generate-key and --hash-key print and exit without building
        // a context or binding a port, so they run while the gateway is up, and Boot never reads
        // --generate-key as a property.
        if (KeygenCommand.handles(args)) {
            System.exit(KeygenCommand.run(args, System.in, System.out, System.err));
        }
        SpringApplication.run(GatewayServerApplication.class, args);
    }
}