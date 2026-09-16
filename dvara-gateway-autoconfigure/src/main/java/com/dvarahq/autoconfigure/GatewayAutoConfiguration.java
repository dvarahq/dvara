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

import com.dvarahq.autoconfigure.routing.DefaultRoutingStrategyFactory;
import com.dvarahq.autoconfigure.guardrail.SimpleTokenEstimator;
import com.dvarahq.autoconfigure.resilience.ResilienceAutoConfiguration;
import com.dvarahq.autoconfigure.security.AnonymousSecurityContext;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Defaults for the extension points the gateway requires: audit, provider health, fallback,
 * security context, token estimation, routing strategy factory and grounding.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean} on its interface, and the class runs at
 * {@code LOWEST_PRECEDENCE}, so it sees what other modules and the application have registered
 * and stands down for them. Without the ordering a condition could be judged before another
 * auto-configuration had registered its bean, and both would end up in the context.
 */
@AutoConfiguration(after = ResilienceAutoConfiguration.class)
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        com.dvarahq.autoconfigure.guardrail.GroundingProperties.class)
@org.springframework.boot.autoconfigure.AutoConfigureOrder(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
public class GatewayAutoConfiguration {

    private static final Logger AUDIT_FALLBACK_LOG = LoggerFactory.getLogger("com.dvarahq.audit.fallback");


    /**
     * The default audit writer: an HMAC-chained local file when {@code dvara.audit.file.path} is
     * set, otherwise a writer that drops events and says so once.
     *
     * <p>Conditional on the interface: {@code AuditWriter} is the extension point for sending the
     * record somewhere else (a database, a queue, a SIEM), and an application or another module
     * that registers one replaces this default without having to mark it {@code @Primary}.
     *
     * @see com.dvarahq.autoconfigure.audit.FileAuditWriter
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(AuditWriter.class)
    public AuditWriter defaultAuditWriter(
            @Value("${dvara.audit.file.path:}") String auditFilePath,
            @Value("${dvara.audit.hmac-secret:}") String hmacSecret) {
        if (auditFilePath != null && !auditFilePath.isBlank()) {
            return new com.dvarahq.autoconfigure.audit.FileAuditWriter(
                    java.nio.file.Path.of(auditFilePath), requireRealSecret(hmacSecret, auditFilePath));
        }
        return droppingAuditWriter();
    }

    /**
     * Refuse the shipped default secret, rather than sign a chain with a key everyone has.
     *
     * <p>A chain signed with {@code default-dev-secret-change-in-production} is not tamper-evident
     * against anyone who has read the repository — which is everyone, once it is public. Writing it
     * anyway would produce a file that reads as evidence and is not, and this is the one moment an
     * operator can be told. Failing at startup is recoverable in seconds; discovering it when the
     * chain is needed is not.
     */
    private static String requireRealSecret(String secret, String path) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "dvara.audit.file.path is set to " + path + " but dvara.audit.hmac-secret is not "
                            + "configured. The chain would be unsigned. Set DVARA_AUDIT_HMAC_SECRET "
                            + "(openssl rand -base64 32), or unset the path to disable audit writing.");
        }
        if (DEV_AUDIT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "dvara.audit.file.path is set to " + path + " but dvara.audit.hmac-secret is "
                            + "still the shipped development default. Everyone has that value, so "
                            + "the chain it signs is not tamper-evident against anyone. Set "
                            + "DVARA_AUDIT_HMAC_SECRET to a real secret (openssl rand -base64 32).");
        }
        return secret;
    }

    /** The value application.yml ships, which must never sign a chain anyone relies on. */
    private static final String DEV_AUDIT_SECRET = "default-dev-secret-change-in-production";

    /**
     * Drops events, loudly once and quietly thereafter.
     *
     * <p>The first is a {@code warn} so a misconfigured install is visible; the rest are {@code
     * debug} so a cluster that meant to run without audit is not flooded.
     */
    private static AuditWriter droppingAuditWriter() {
        java.util.concurrent.atomic.AtomicBoolean firstWarningEmitted = new java.util.concurrent.atomic.AtomicBoolean(false);
        return event -> {
            if (firstWarningEmitted.compareAndSet(false, true)) {
                AUDIT_FALLBACK_LOG.warn(
                        "Audit events are being dropped: no audit engine is present in this build and "
                                + "dvara.audit.file.path is not set. Set it to write an "
                                + "HMAC-chained audit log to local disk. "
                                + "This warning is logged once; subsequent drops log at DEBUG. "
                                + "First dropped event: type={} workspace={}",
                        event.eventType(), event.workspaceId());
            } else if (AUDIT_FALLBACK_LOG.isDebugEnabled()) {
                AUDIT_FALLBACK_LOG.debug(
                        "Audit event dropped: type={} workspace={} payload={}",
                        event.eventType(), event.workspaceId(), event.payload());
            }
        };
    }

    /**
     * Every provider healthy, which is true of a build with no circuit breakers.
     *
     * <p>Conditional on the interface so it is the only bean of this type.
     * {@code ResilienceAutoConfiguration} registers the Resilience4j-backed registry when resilience
     * is enabled, and this class is ordered after it, so this one stands down rather than sitting
     * beside it.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ProviderHealthRegistry.class)
    public ProviderHealthRegistry alwaysHealthyRegistry() {
        return providerName -> ProviderHealthStatus.HEALTHY;
    }

    /**
     * No fallback, for a build that has switched resilience off.
     *
     * <p>{@code ResilienceAutoConfiguration} owns the resolver whenever resilience is enabled and
     * runs before this class, so this bean is only defined when
     * {@code dvara.llm-gateway.resilience.enabled=false}. Fallback is a resilience behaviour, so
     * with resilience off there are no candidates.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(FallbackResolver.class)
    public FallbackResolver disabledFallbackResolver() {
        return (request, failedProvider, allProviders) -> List.of();
    }





    /**
     * Nobody is authenticated, which is true of a gateway with no security wired.
     *
     * <p>Conditional so that an application supplying its own {@code SecurityContext} replaces
     * this one instead of colliding with it.
     */
    @Bean
    @ConditionalOnMissingBean(SecurityContext.class)
    public SecurityContext anonymousSecurityContext() {
        return new AnonymousSecurityContext();
    }

    /*
     * There is deliberately no PiiTokenizer default here. With no bean, PiiScanService refuses
     * TOKENIZE with PII_TOKENIZE_UNAVAILABLE. A no-op tokenizer would accept the mapping and
     * discard it, turning a reversible tokenization into an irreversible one with no error and no
     * audit event. REDACT stores nothing and needs nothing from this slot.
     */

    /**
     * A character-heuristic estimator, for a build with no better one on the classpath.
     *
     * <p>Conditional on the interface: the policy module ships a BPE estimator, and this
     * configuration runs last, so the condition sees that one when it is present.
     */
    @Bean
    @ConditionalOnMissingBean(TokenEstimator.class)
    public TokenEstimator simpleTokenEstimator() {
        return new SimpleTokenEstimator();
    }


    /** A real implementation, and the default. Steps aside for an application's own. */
    @Bean
    @ConditionalOnMissingBean(RoutingStrategyFactory.class)
    public RoutingStrategyFactory defaultRoutingStrategyFactory() {
        return new DefaultRoutingStrategyFactory();
    }



    /**
     * The install-wide grounding posture, bound from
     * {@code dvara.llm-gateway.guardrail.grounding.*}; off for most installs.
     *
     * <p>{@code @ConditionalOnMissingBean} because a module that supplies a grounding detector
     * supplies its own config bean from the same keys.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.dvarahq.core.guardrail.GroundingConfig configuredGroundingConfig(
            com.dvarahq.autoconfigure.guardrail.GroundingProperties grounding) {
        return new com.dvarahq.core.guardrail.GroundingConfig(grounding.isEnabled(),
                grounding.getAction(), grounding.getMaxSources(), grounding.getMaxSourceLength());
    }
}
