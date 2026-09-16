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
package com.dvarahq.core.region;

/**
 * Health status of a region in the multi-region topology.
 *
 * <p>{@link #UNKNOWN} is the one to read carefully: it means <b>this registry has no information
 * about that region</b>, which is not the same as knowing it is down. Only a registry that actually
 * tracks other regions can say {@link #UNREACHABLE}; a registry that tracks one region (see
 * {@code SingleRegionHealthRegistry}) must not be read as excluding every other one.
 *
 * <p>{@link #DEGRADED} is produced by no implementation in this repository. It exists for a
 * registry that aggregates heartbeats and can tell "slow" from "gone".
 */
public enum RegionHealthStatus {
    HEALTHY,
    DEGRADED,

    /** No information about this region. Not a claim that it is unavailable. */
    UNKNOWN,

    /** Known to be unreachable — a positive statement, which only a tracking registry can make. */
    UNREACHABLE
}