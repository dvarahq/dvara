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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data plane must not carry the control-plane marker.
 *
 * <p>Only a test in the assembled application can check this: the marker could arrive through a
 * transitive dependency, a shaded jar or a copied resources directory, with no error and no log
 * line. It switches on scheduled sweeps that delete and rewrite rows, and if it appeared here every
 * gateway replica would run all of them concurrently.
 */
class ControlPlaneMarkerAbsentTest {

    @Test
    void theControlPlaneMarkerIsNotOnThisApplicationsClasspath() {
        assertThat(getClass().getClassLoader().getResource("META-INF/dvara/control-plane.marker"))
                .as("the gateway is a data-plane app: it must not run credential grace expiry, "
                        + "approval orphan sweeps, PII key retention or access-token expiry")
                .isNull();
    }
}