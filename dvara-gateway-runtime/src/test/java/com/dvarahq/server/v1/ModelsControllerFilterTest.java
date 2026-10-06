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
import com.dvarahq.core.provider.ModelContextLimits;
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ModelListFilter;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.AnthropicModelListResponse;
import com.dvarahq.server.v1.dto.ModelListResponse;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The model list shows only what the caller may use, each model with its own window, and an Anthropic client
 * sees only Claude models in Anthropic's shape.
 */
class ModelsControllerFilterTest {

    private static LlmProvider provider(String name, int window, ModelInfo... models) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        when(p.capabilities()).thenReturn(new ProviderCapabilities(true, true, true, true, true, window));
        when(p.listModels()).thenReturn(List.of(models));
        return p;
    }

    private static ProviderDispatcher dispatcher() {
        List<LlmProvider> providers = List.of(
                provider("openai", 128_000,
                        new ModelInfo("gpt-4o", "openai", 1L), new ModelInfo("gpt-4.1", "openai", 2L)),
                provider("anthropic", 200_000,
                        new ModelInfo("claude-sonnet-4-5", "anthropic", 1_759_000_000L),
                        new ModelInfo("claude-opus-4-1", "anthropic", 0L)),
                provider("ollama", 32_000,
                        new ModelInfo("claude-lookalike", "ollama", 3L)));
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        when(dispatcher.allProviders()).thenReturn(providers);
        return dispatcher;
    }

    private static ModelsController controller(List<ModelListFilter> filters, List<ModelContextLimits> limits) {
        return new ModelsController(dispatcher(), Duration.ofSeconds(5), filters, limits);
    }

    private static MockHttpServletRequest request(String workspace) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        if (workspace != null) {
            request.setAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR, workspace);
        }
        return request;
    }

    private static List<String> ids(ModelsController controller, MockHttpServletRequest request) {
        ModelListResponse body = (ModelListResponse) controller.models(request).getBody();
        return body.getData().stream().map(ModelListResponse.ModelData::getId).toList();
    }

    /** Drops the named models. */
    private static ModelListFilter without(String... dropped) {
        List<String> gone = List.of(dropped);
        return (workspace, models) -> models.stream().filter(m -> !gone.contains(m.id())).toList();
    }

    @Test
    void withNoFilterEveryModelIsListed() {
        assertThat(ids(controller(List.of(), List.of()), request("ws-1")))
                .containsExactly("gpt-4o", "gpt-4.1", "claude-sonnet-4-5", "claude-opus-4-1", "claude-lookalike");
    }

    @Test
    void aFilterRemovesTheModelsTheCallersWorkspaceMayNotUse() {
        AtomicReference<String> seen = new AtomicReference<>();
        ModelListFilter perWorkspace = (workspace, models) -> {
            seen.set(workspace);
            return "ws-1".equals(workspace) ? without("gpt-4o").filter(workspace, models) : models;
        };
        ModelsController controller = controller(List.of(perWorkspace), List.of());

        assertThat(ids(controller, request("ws-1")))
                .containsExactly("gpt-4.1", "claude-sonnet-4-5", "claude-opus-4-1", "claude-lookalike");
        assertThat(seen.get()).as("the workspace of the caller's key").isEqualTo("ws-1");
        assertThat(ids(controller, request("ws-2"))).contains("gpt-4o");
    }

    @Test
    void filtersApplyInOrderEachToWhatTheOneBeforeKept() {
        List<List<String>> inputs = new ArrayList<>();
        ModelListFilter recording = (workspace, models) -> {
            inputs.add(models.stream().map(ModelInfo::id).toList());
            return models;
        };
        ModelsController controller = controller(List.of(without("gpt-4o"), recording, without("gpt-4.1")),
                List.of());

        assertThat(ids(controller, request("ws-1")))
                .containsExactly("claude-sonnet-4-5", "claude-opus-4-1", "claude-lookalike");
        assertThat(inputs).containsExactly(
                List.of("gpt-4.1", "claude-sonnet-4-5", "claude-opus-4-1", "claude-lookalike"));
    }

    @Test
    void aFilterThatFailsIsSkippedAndTheOthersStillApply() {
        ModelListFilter broken = (workspace, models) -> {
            throw new IllegalStateException("store unreachable");
        };
        ModelListFilter none = (workspace, models) -> null;
        ModelsController controller = controller(List.of(broken, none, without("gpt-4o")), List.of());

        assertThat(ids(controller, request("ws-1")))
                .containsExactly("gpt-4.1", "claude-sonnet-4-5", "claude-opus-4-1", "claude-lookalike");
    }

    @Test
    void aFilterCanOnlyRemoveNotAdd() {
        ModelListFilter adding = (workspace, models) -> {
            List<ModelInfo> more = new ArrayList<>(models);
            more.add(new ModelInfo("made-up", "nobody", 0L));
            return more;
        };

        assertThat(ids(controller(List.of(adding), List.of()), request("ws-1"))).doesNotContain("made-up");
    }

    @Test
    void eachModelShowsItsOwnContextWindowWhereOneIsKnown() {
        ModelContextLimits catalogue = (provider, model) ->
                "openai".equals(provider) && "gpt-4.1".equals(model) ? OptionalInt.of(1_047_576) : OptionalInt.empty();
        ModelsController controller = controller(List.of(), List.of(catalogue));

        ModelListResponse body = (ModelListResponse) controller.models(request("ws-1")).getBody();

        assertThat(body.getData()).extracting(d -> d.getId() + "=" + d.getCapabilities().getMaxContextTokens())
                .contains("gpt-4.1=1047576", "gpt-4o=128000", "claude-sonnet-4-5=200000");
    }

    @Test
    void anAnthropicClientSeesOnlyClaudeModelsAnthropicServesInAnthropicsShape() {
        MockHttpServletRequest request = request("ws-1");
        request.addHeader("anthropic-version", "2023-06-01");

        Object body = controller(List.of(), List.of()).models(request).getBody();

        assertThat(body).isInstanceOf(AnthropicModelListResponse.class);
        AnthropicModelListResponse list = (AnthropicModelListResponse) body;
        assertThat(list.data()).extracting(AnthropicModelListResponse.Model::id)
                .as("no OpenAI model, and no other provider's model under a Claude-like name")
                .containsExactly("claude-sonnet-4-5", "claude-opus-4-1");
        assertThat(list.data().getFirst().type()).isEqualTo("model");
        assertThat(list.data().getFirst().displayName()).isEqualTo("claude-sonnet-4-5");
        assertThat(list.data().getFirst().createdAt()).isEqualTo("2025-09-27T19:06:40Z");
        assertThat(list.data().getLast().createdAt()).isEqualTo("1970-01-01T00:00:00Z");
        assertThat(list.hasMore()).isFalse();
        assertThat(list.firstId()).isEqualTo("claude-sonnet-4-5");
        assertThat(list.lastId()).isEqualTo("claude-opus-4-1");
    }

    @Test
    void anAnthropicClientsListIsNarrowedByTheFiltersToo() {
        MockHttpServletRequest request = request("ws-1");
        request.addHeader("anthropic-version", "2023-06-01");

        AnthropicModelListResponse list = (AnthropicModelListResponse)
                controller(List.of(without("claude-opus-4-1")), List.of()).models(request).getBody();

        assertThat(list.data()).extracting(AnthropicModelListResponse.Model::id).containsExactly("claude-sonnet-4-5");
        assertThat(list.lastId()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void anAnthropicClientWithNoClaudeModelGetsAnEmptyPage() {
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        LlmProvider openai = provider("openai", 128_000, new ModelInfo("gpt-4o", "openai", 1L));
        when(dispatcher.allProviders()).thenReturn(List.of(openai));
        MockHttpServletRequest request = request(null);
        request.addHeader("anthropic-version", "2023-06-01");

        AnthropicModelListResponse list = (AnthropicModelListResponse)
                new ModelsController(dispatcher).models(request).getBody();

        assertThat(list.data()).isEmpty();
        assertThat(list.firstId()).isNull();
        assertThat(list.lastId()).isNull();
    }
}
