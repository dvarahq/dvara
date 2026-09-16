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
package com.dvarahq.core.security;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Provides access to the current authenticated user's identity.
 *
 * <p>Default ({@code AnonymousSecurityContext}) returns empty values. Another module or the
 * application may register an implementation that reads from Spring Security's
 * {@code SecurityContextHolder}.</p>
 */
public interface SecurityContext {

    /**
     * Returns the authenticated user's unique identifier, or empty if unauthenticated.
     */
    Optional<String> currentUserId();

    /**
     * Returns the authenticated user's display name, or empty if unauthenticated.
     */
    Optional<String> currentUserName();

    /**
     * Returns the set of roles granted to the current user. Empty set if unauthenticated.
     */
    Set<String> currentRoles();

    /**
     * Returns {@code true} when the current request has an authenticated user.
     */
    boolean isAuthenticated();

    /**
     * Returns the workspace identifier associated with the current user, or empty.
     */
    Optional<String> currentWorkspaceId();

    /**
     * Returns additional attributes from the authenticated principal (e.g. JWT claims)
     * for future attribute-based access control (ABAC).
     *
     * <p>Default implementation returns an empty map.</p>
     */
    default Map<String, String> currentAttributes() {
        return Map.of();
    }
}