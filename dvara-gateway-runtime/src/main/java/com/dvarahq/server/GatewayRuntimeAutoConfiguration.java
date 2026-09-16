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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

/**
 * Registers the gateway's controllers, filters, dispatcher and metrics in whatever application
 * includes this module. An auto-configuration is found regardless of where the host application's
 * own component scan points, so a host in another package still gets the full request path.
 *
 * <p>The scan covers {@code com.dvarahq.server}, a package this module owns outright, so it cannot
 * pick up beans a host did not ask for. {@code GatewayServerApplication} does not scan the same
 * package itself; if it did, every bean would be defined twice and Spring Boot would refuse the
 * duplicate definitions.
 *
 * <p>Servlet only: everything here is controllers and servlet filters, which would fail to wire in a
 * non-web context rather than be usefully absent.
 *
 * <p>The two exclude filters are the ones {@code @SpringBootApplication} applies, and this scan
 * stands in for that one. {@link TypeExcludeFilter} keeps {@code @TestConfiguration} classes on the
 * test classpath out of the scan; {@link AutoConfigurationExcludeFilter} stops auto-configurations in
 * this package from being registered a second time as ordinary {@code @Configuration} classes.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ComponentScan(
        basePackages = "com.dvarahq.server",
        excludeFilters = {
                @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
public class GatewayRuntimeAutoConfiguration {

    /**
     * The default prompt-template resolver, declared as a {@code @Bean} rather than found by the
     * scan because a component scan cannot express the condition. An application that registers
     * its own {@code PromptTemplateResolver} replaces this one without any further annotation, so
     * exactly one resolver exists in every configuration.
     */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            com.dvarahq.core.prompt.PromptTemplateResolver.class)
    com.dvarahq.core.prompt.PromptTemplateResolver defaultPromptTemplateResolver(
            com.dvarahq.core.prompt.PromptTemplateRepository repository) {
        return new com.dvarahq.server.prompt.DefaultPromptTemplateResolver(repository);
    }
}