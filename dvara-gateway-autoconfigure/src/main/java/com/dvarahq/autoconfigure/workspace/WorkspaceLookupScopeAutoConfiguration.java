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
package com.dvarahq.autoconfigure.workspace;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers the per-request workspace memo.
 *
 * <p>{@code @ConditionalOnWebApplication(SERVLET)} is load-bearing: this registers a servlet
 * {@code FilterRegistrationBean}, and a non-web context that pulls in
 * {@code dvara-gateway-autoconfigure} would otherwise introspect this class and fail with
 * {@code NoClassDefFoundError: jakarta/servlet/Filter}. The condition is evaluated at class level
 * before {@code @Bean} introspection, so the auto-configuration is skipped wholesale outside a
 * servlet app. Every auto-configuration here that wires servlet beans carries the same guard.
 *
 * <p>No property gates it. The memo's lifetime is one request, and a workspace row cannot
 * meaningfully change inside one, so there is no posture for an operator to choose between.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkspaceLookupScopeAutoConfiguration {

    @Bean
    public FilterRegistrationBean<WorkspaceLookupScopeFilter> workspaceLookupScopeFilter() {
        FilterRegistrationBean<WorkspaceLookupScopeFilter> registration =
                new FilterRegistrationBean<>(new WorkspaceLookupScopeFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}