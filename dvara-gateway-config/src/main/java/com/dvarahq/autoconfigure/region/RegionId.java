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
package com.dvarahq.autoconfigure.region;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one place a region id is resolved.
 *
 * <p>Two properties name the region. {@code dvara.region.id} is bound by
 * {@code GatewayRegionProperties} and is the preferred key; {@code dvara.llm-gateway.region.id} is
 * a legacy key that is still honoured. Resolving both here means either one configures the whole
 * region posture: residency enforcement and config-bundle scope.
 *
 * <p>Disagreement refuses the boot rather than picking a winner: an install that sets both to
 * different values holds two beliefs about where it is, and every outcome of guessing is worse than
 * saying so. An install on the legacy key alone keeps working, with a one-time WARN naming the
 * replacement.
 */
public final class RegionId {

    private static final Logger log = LoggerFactory.getLogger(RegionId.class);

    /** The preferred key, bound by {@code GatewayRegionProperties}. */
    public static final String PROPERTY = "dvara.region.id";

    /** The legacy key, still honoured. */
    public static final String LEGACY_PROPERTY = "dvara.llm-gateway.region.id";

    private static final AtomicBoolean LEGACY_WARNED = new AtomicBoolean();

    private RegionId() {
    }

    /**
     * @return the configured region id, or {@code null} when neither key is set
     * @throws IllegalStateException when both are set and disagree
     */
    public static String resolve(Environment environment) {
        String current = trimToNull(environment.getProperty(PROPERTY));
        String legacy = trimToNull(environment.getProperty(LEGACY_PROPERTY));

        if (current != null && legacy != null && !current.equals(legacy)) {
            throw new IllegalStateException(
                    "Conflicting region configuration: " + PROPERTY + "=" + current + " but "
                            + LEGACY_PROPERTY + "=" + legacy + ". These are the same setting — one "
                            + "drives data-residency enforcement, the other the config-bundle scope, "
                            + "and a pod cannot be in two regions. Set " + PROPERTY + " only.");
        }
        if (current != null) {
            return current;
        }
        if (legacy != null && LEGACY_WARNED.compareAndSet(false, true)) {
            log.warn("{} is deprecated — use {}. Both now configure the whole region posture "
                            + "(residency enforcement and config-bundle scope); until the two were unified this key "
                            + "set only the scope, leaving residency unable to refuse a cross-region "
                            + "provider.", LEGACY_PROPERTY, PROPERTY);
        }
        return legacy;
    }

    /** As {@link #resolve} but never null — {@code fallback} when neither key is set. */
    public static String resolveOrDefault(Environment environment, String fallback) {
        String resolved = resolve(environment);
        return resolved == null ? fallback : resolved;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}