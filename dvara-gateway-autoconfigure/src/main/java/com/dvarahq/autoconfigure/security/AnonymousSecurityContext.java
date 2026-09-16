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
package com.dvarahq.autoconfigure.security;

import com.dvarahq.core.security.SecurityContext;

import java.util.Optional;
import java.util.Set;

/**
 * Default {@link SecurityContext} that always reports unauthenticated. Another module or the
 * application replaces it by registering its own {@code SecurityContext} bean.
 */
public class AnonymousSecurityContext implements SecurityContext {

    @Override
    public Optional<String> currentUserId() {
        return Optional.empty();
    }

    @Override
    public Optional<String> currentUserName() {
        return Optional.empty();
    }

    @Override
    public Set<String> currentRoles() {
        return Set.of();
    }

    @Override
    public boolean isAuthenticated() {
        return false;
    }

    @Override
    public Optional<String> currentWorkspaceId() {
        return Optional.empty();
    }
}