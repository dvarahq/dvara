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

import com.dvarahq.autoconfigure.RegionAutoConfiguration;
import com.dvarahq.core.region.RegionContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two property keys name the gateway's region. Both must resolve to the same single region so that
 * every consumer, {@code RegionContext} and the residency check included, sees the same value.
 */
class RegionIdTest {

    @Test
    void theSurvivingKeyResolves() {
        assertThat(RegionId.resolve(new MockEnvironment().withProperty(RegionId.PROPERTY, "eu")))
                .isEqualTo("eu");
    }

    @Test
    void theLegacyKeyStillResolvesSoNoInstallBreaks() {
        assertThat(RegionId.resolve(new MockEnvironment().withProperty(RegionId.LEGACY_PROPERTY, "eu")))
                .as("adopting this must not require a configuration change")
                .isEqualTo("eu");
    }

    @Test
    void neitherKeySetIsNotAnError() {
        assertThat(RegionId.resolve(new MockEnvironment())).isNull();
        assertThat(RegionId.resolveOrDefault(new MockEnvironment(), "default")).isEqualTo("default");
    }

    @Test
    void agreementIsFine() {
        assertThat(RegionId.resolve(new MockEnvironment()
                .withProperty(RegionId.PROPERTY, "eu")
                .withProperty(RegionId.LEGACY_PROPERTY, "eu")))
                .isEqualTo("eu");
    }

    @Test
    void disagreementRefusesRatherThanPickingAWinner() {
        // Every outcome of guessing is worse than saying so: prefer one and residency silently
        // follows a region the bundle does not, prefer the other and the reverse.
        assertThatThrownBy(() -> RegionId.resolve(new MockEnvironment()
                .withProperty(RegionId.PROPERTY, "eu")
                .withProperty(RegionId.LEGACY_PROPERTY, "us")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eu")
                .hasMessageContaining("us")
                .hasMessageContaining("cannot be in two regions");
    }

    @Test
    void blankIsTreatedAsUnsetOnBothKeys() {
        // A blank value is how an unset env var arrives, and it must not read as a disagreement.
        assertThat(RegionId.resolve(new MockEnvironment()
                .withProperty(RegionId.PROPERTY, "  ")
                .withProperty(RegionId.LEGACY_PROPERTY, "eu")))
                .isEqualTo("eu");
    }

    @Test
    void residencyIsEnforceableOnAnInstallThatOnlySetTheLegacyKey() {
        // Setting only the legacy key must still populate RegionContext; residency checks
        // have nothing to compare against otherwise.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RegionAutoConfiguration.class))
                .withPropertyValues(RegionId.LEGACY_PROPERTY + "=eu")
                .run(context -> assertThat(context.getBean(RegionContext.class).currentRegion())
                        .as("residency cannot refuse a cross-region provider without a region")
                        .contains("eu"));
    }

    @Test
    void residencyStillWorksOnTheSurvivingKey() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RegionAutoConfiguration.class))
                .withPropertyValues(RegionId.PROPERTY + "=us")
                .run(context -> assertThat(context.getBean(RegionContext.class).currentRegion())
                        .contains("us"));
    }

    @Test
    void noRegionConfiguredLeavesResidencyUnset() {
        // Single-region installs set nothing, and must not acquire a region they did not ask for.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RegionAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(RegionContext.class).currentRegion())
                        .isEmpty());
    }
}