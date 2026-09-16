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
package com.dvarahq.ratelimit;

import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.WorkspaceRateLimitResolver;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.settings.WorkspaceRateLimitsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Makes a workspace's own rate limit apply, whatever is storing workspaces.
 *
 * <p>A {@code WorkspaceRepository} can come from more than one auto-configuration, so this asks an
 * {@link ObjectProvider} for it at construction rather than conditioning on the bean: bean
 * definitions are all registered before any bean is instantiated, so the answer does not depend on
 * ordering. With no workspace store at all, the resolver returns {@link EffectiveRateLimit#NONE},
 * because there is no override to read.
 */
@AutoConfiguration
public class WorkspaceRateLimitResolverAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(WorkspaceRateLimitResolverAutoConfiguration.class);

    /**
     * The default resolver, registered only when nothing else supplies one.
     *
     * <p>A resolver registered by the application or by another module stands this one down: the
     * condition is evaluated after application beans and after earlier auto-configurations. It is
     * not {@code @Primary}, so if two resolvers ever registered, the other one's {@code @Primary}
     * would decide, or the context would fail at startup rather than pick one silently.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    WorkspaceRateLimitResolver workspaceRateLimitResolver(
            ObjectProvider<WorkspaceRepository> workspaces,
            ObjectProvider<WorkspaceRateLimitsRepository> workspaceLimits,
            @Value("${dvara.llm-gateway.rate-limit.override-cache-ttl-seconds:5}") long cacheTtlSeconds) {

        WorkspaceRepository repository = workspaces.getIfAvailable();
        if (repository == null) {
            log.debug("No workspace store, so per-workspace rate-limit overrides cannot be read; "
                    + "every key uses the global per-key limits.");
            return workspaceId -> EffectiveRateLimit.NONE;
        }
        return new MetadataWorkspaceRateLimitResolver(repository, cacheTtlSeconds,
                workspaceLimits.getIfAvailable());
    }
}
