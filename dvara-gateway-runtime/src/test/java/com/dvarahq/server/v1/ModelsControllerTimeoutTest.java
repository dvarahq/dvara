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
package com.dvarahq.server.v1;

import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.ModelListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A slow provider cannot hold /v1/models for every caller: it is left out and the rest are served within the timeout. */
class ModelsControllerTimeoutTest {

    private static LlmProvider provider(String name, long delayMs, String model) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        when(p.capabilities()).thenReturn(new ProviderCapabilities(true, false, false, false, false, 1000));
        when(p.listModels()).thenAnswer(inv -> {
            Thread.sleep(delayMs);
            return List.of(new ModelInfo(model, name, 1L));
        });
        return p;
    }

    @Test
    void aSlowProviderIsLeftOutAndTheOthersAreServedWithinTheTimeout() {
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        // Built before stubbing the dispatcher: creating a mock inside when(...) is a nested stubbing.
        List<LlmProvider> providers = List.of(
                provider("slow", 5_000, "slow-model"),
                provider("fast-a", 50, "a-model"),
                provider("fast-b", 50, "b-model"));
        when(dispatcher.allProviders()).thenReturn(providers);
        ModelsController controller = new ModelsController(dispatcher, Duration.ofMillis(400));

        long start = System.nanoTime();
        ModelListResponse body = controller.models(new MockHttpServletRequest()).getBody();
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(body.getData()).extracting(ModelListResponse.ModelData::getId)
                .containsExactly("a-model", "b-model");
        assertThat(tookMs).as("bounded by the timeout, not by the slow provider").isLessThan(2_000);
    }

    @Test
    void providersAreAskedTogetherNotOneAfterAnother() {
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        List<LlmProvider> providers = List.of(
                provider("a", 300, "a-model"), provider("b", 300, "b-model"), provider("c", 300, "c-model"));
        when(dispatcher.allProviders()).thenReturn(providers);
        ModelsController controller = new ModelsController(dispatcher, Duration.ofSeconds(5));

        long start = System.nanoTime();
        ModelListResponse body = controller.models(new MockHttpServletRequest()).getBody();
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(body.getData()).hasSize(3);
        assertThat(tookMs).as("three 300 ms listings in parallel, not 900 ms").isLessThan(800);
    }
}
