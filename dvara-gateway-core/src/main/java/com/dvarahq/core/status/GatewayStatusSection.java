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
package com.dvarahq.core.status;

import java.util.List;

/**
 * One block of {@code /actuator/gateway-status} contributed by a module the endpoint does not know.
 *
 * <p>The endpoint renders what every gateway has: providers, routes, rate limits and region.
 * A module that runs a channel the endpoint cannot see — a config bundle client, a revocation list,
 * an audit spool, a stale-read cache — registers a bean of this type. Its {@link #value()} is written
 * into the response under {@link #key()}, and its {@link #warnings()} are appended to the response's
 * warning list. A section with a null value contributes warnings only.
 *
 * <p><b>A section that fails costs its own block and nothing else.</b> Both methods are called inside
 * a guard: a throw is caught, turned into a warning naming this class and the reason, and the rest of
 * the payload still renders. That matters because these sections report channels, and the moment a
 * channel is broken is the moment an operator opens this endpoint — a spool whose file is corrupt
 * taking the whole status payload down with it would hide the providers, routes and region as well as
 * itself. Do not rely on the guard: a section that cannot answer should say so in its own
 * {@link #warnings()} rather than throw, because a warning it writes reads better than one the
 * endpoint writes about it.
 *
 * <p><b>{@link #key()} must be unique across every registered section.</b> The first section claiming
 * a key keeps it and a later one is dropped with a warning naming both; the endpoint cannot merge two
 * blocks without inventing a rule for conflicts. Pick a key named for the channel, not for the module.
 */
public interface GatewayStatusSection {

    /** The JSON property this section is written under. */
    String key();

    /** The section body, serialised as-is, or null to contribute warnings only. */
    Object value();

    /** Operator-facing warnings this section wants on the response, in order. */
    default List<String> warnings() {
        return List.of();
    }
}
