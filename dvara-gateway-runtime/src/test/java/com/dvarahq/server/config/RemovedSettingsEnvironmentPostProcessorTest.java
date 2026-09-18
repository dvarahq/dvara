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
package com.dvarahq.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The setting that once allowed keyless requests is refused before the context starts. Refused,
 * not ignored: a value of {@code false} was relied on, and the operator must learn at startup, not
 * from their callers, that keyless requests are no longer served.
 */
class RemovedSettingsEnvironmentPostProcessorTest {

    private static final String KEY = RemovedSettingsEnvironmentPostProcessor.REQUIRE_API_KEY;

    @Test
    void falseRefusesToStart_andTheMessageNamesTheSettingTheReleaseAndTheWayOut() {
        assertThatThrownBy(() -> run(Map.of(KEY, "false")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY)
                .hasMessageContaining("DVARA_LLM_GATEWAY_REQUIRE_API_KEY")
                .hasMessageContaining("removed in 1.8.0")
                .hasMessageContaining("--generate-key");
    }

    /** "no", "0", "off" all meant keyless; none of them is a reason to start serving nothing. */
    @Test
    void anythingButTrueIsRefused() {
        for (String value : new String[] {"no", "0", "off", "FALSE", " false "}) {
            assertThatThrownBy(() -> run(Map.of(KEY, value))).as(value).isInstanceOf(IllegalStateException.class);
        }
    }

    /** A deployment that already required keys has lost nothing; it starts, and is told the line is dead. */
    @Test
    void trueStarts() {
        assertThatCode(() -> run(Map.of(KEY, "true"))).doesNotThrowAnyException();
        assertThatCode(() -> run(Map.of(KEY, "TRUE"))).doesNotThrowAnyException();
    }

    @Test
    void absentOrBlankIsNotAnOpinion() {
        assertThatCode(() -> run(Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> run(Map.of(KEY, ""))).doesNotThrowAnyException();
    }

    private static void run(Map<String, Object> properties) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        new RemovedSettingsEnvironmentPostProcessor(new DeferredLogs()).postProcessEnvironment(environment, null);
    }
}
