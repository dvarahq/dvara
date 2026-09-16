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
package com.dvarahq.core.routing;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolDefinition;
import java.util.Map;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingEngineTest {

    @Test
    void exactPatternMatch_delegatesToRouteStrategy() {
        RouteConfig config = RouteConfig.builder().modelPattern("gpt-4o").build();
        RoutingStrategy routeStrategy = (req, providers) -> stubProvider("route-provider");
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default-provider");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        List<LlmProvider> providers = List.of(stubProvider("route-provider"), stubProvider("default-provider"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThat(engine.route(request, providers).name()).isEqualTo("route-provider");
    }

    @Test
    void wildcardPatternMatch_matchesPrefix() {
        RouteConfig config = RouteConfig.builder().modelPattern("gpt-*").build();
        RoutingStrategy routeStrategy = (req, providers) -> stubProvider("route-provider");
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default-provider");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        List<LlmProvider> providers = List.of(stubProvider("route-provider"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o-mini").build();

        assertThat(engine.route(request, providers).name()).isEqualTo("route-provider");
    }

    @Test
    void noMatchingRoute_fallsBackToDefault() {
        RouteConfig config = RouteConfig.builder().modelPattern("gpt-*").build();
        RoutingStrategy routeStrategy = (req, providers) -> stubProvider("route-provider");
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default-provider");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        List<LlmProvider> providers = List.of(stubProvider("default-provider"));
        ChatRequest request = ChatRequest.builder().model("claude-3-opus").build();

        assertThat(engine.route(request, providers).name()).isEqualTo("default-provider");
    }

    @Test
    void nullModel_fallsBackToDefault() {
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default-provider");
        RoutingEngine engine = new RoutingEngine(defaultStrategy, List.of());

        List<LlmProvider> providers = List.of(stubProvider("default-provider"));
        ChatRequest request = ChatRequest.builder().model(null).build();

        assertThat(engine.route(request, providers).name()).isEqualTo("default-provider");
    }

    @Test
    void modelPinning_replacesModelInRequest() {
        RouteConfig config = RouteConfig.builder()
                .modelPattern("gpt-*")
                .pinnedModelVersion("gpt-4o-2024-05-13")
                .build();

        // Capture the request model the route strategy receives
        String[] receivedModel = new String[1];
        RoutingStrategy routeStrategy = (req, providers) -> {
            receivedModel[0] = req.getModel();
            return stubProvider("openai");
        };
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        List<LlmProvider> providers = List.of(stubProvider("openai"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        engine.route(request, providers);

        assertThat(receivedModel[0]).isEqualTo("gpt-4o-2024-05-13");
    }

    @Test
    void matchedRoute_setOnContextOnMatch() {
        RouteConfig config = RouteConfig.builder().id("route-1").modelPattern("gpt-*").build();
        RoutingStrategy routeStrategy = (req, providers) -> stubProvider("openai");
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        RequestContext ctx = RequestContext.builder().build();
        List<LlmProvider> providers = List.of(stubProvider("openai"));
        engine.route(ChatRequest.builder().model("gpt-4o").build(), providers, ctx);

        assertThat(ctx.getMatchedRoute()).isNotNull();
        assertThat(ctx.getMatchedRoute().getId()).isEqualTo("route-1");
    }

    @Test
    void matchedRoute_nullOnContextOnNoMatch() {
        RouteConfig config = RouteConfig.builder().modelPattern("gpt-*").build();
        RoutingStrategy routeStrategy = (req, providers) -> stubProvider("openai");
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        RequestContext ctx = RequestContext.builder().build();
        List<LlmProvider> providers = List.of(stubProvider("default"));
        engine.route(ChatRequest.builder().model("claude-3").build(), providers, ctx);

        assertThat(ctx.getMatchedRoute()).isNull();
    }

    @Test
    void updateRoutes_replacesExistingRoutes() {
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default");
        RoutingEngine engine = new RoutingEngine(defaultStrategy, List.of());

        assertThat(engine.currentRoutes()).isEmpty();

        RouteConfig config = RouteConfig.builder().modelPattern("gpt-*").build();
        RoutingStrategy routeStrategy = (req, providers) -> stubProvider("openai");
        engine.updateRoutes(List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        assertThat(engine.currentRoutes()).hasSize(1);
    }

    @Test
    void firstMatchingRouteWins() {
        RouteConfig broad = RouteConfig.builder().id("broad").modelPattern("gpt-*").build();
        RouteConfig specific = RouteConfig.builder().id("specific").modelPattern("gpt-4o").build();
        RoutingStrategy broadStrategy = (req, providers) -> stubProvider("broad-provider");
        RoutingStrategy specificStrategy = (req, providers) -> stubProvider("specific-provider");

        // Broad is listed first — it should win
        RoutingEngine engine = new RoutingEngine(
                (req, providers) -> stubProvider("default"),
                List.of(
                        new RoutingEngine.ResolvedRoute(broad, broadStrategy),
                        new RoutingEngine.ResolvedRoute(specific, specificStrategy)
                ));

        List<LlmProvider> providers = List.of(stubProvider("broad-provider"), stubProvider("specific-provider"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThat(engine.route(request, providers).name()).isEqualTo("broad-provider");
    }

    @Test
    void resolvedModel_setByStrategyViaContext() {
        RouteConfig config = RouteConfig.builder().modelPattern("auto-*").build();
        // Strategy that overrides the 3-arg route to set resolvedModel
        RoutingStrategy routeStrategy = new RoutingStrategy() {
            @Override
            public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
                return stubProvider("openai");
            }
            @Override
            public LlmProvider route(ChatRequest request, List<LlmProvider> providers, RequestContext ctx) {
                ctx.setResolvedModel("gpt-4o-mini");
                return stubProvider("openai");
            }
        };
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        RequestContext ctx = RequestContext.builder().build();
        engine.route(ChatRequest.builder().model("auto-route").build(),
                List.of(stubProvider("openai")), ctx);

        assertThat(ctx.getResolvedModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void noResolvedModel_whenStrategyDoesNotSetIt() {
        RouteConfig config = RouteConfig.builder().modelPattern("gpt-*").build();
        RoutingStrategy routeStrategy = (req, providers) -> stubProvider("openai");
        RoutingStrategy defaultStrategy = (req, providers) -> stubProvider("default");

        RoutingEngine engine = new RoutingEngine(defaultStrategy,
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        RequestContext ctx = RequestContext.builder().build();
        engine.route(ChatRequest.builder().model("gpt-4o").build(),
                List.of(stubProvider("openai")), ctx);

        assertThat(ctx.getResolvedModel()).isNull();
    }

    /**
     * A route change is visible all at once: no request sees an empty route table.
     *
     * <p>Every route edit on a running fleet goes through {@code updateRoutes}, from the file-store
     * publisher, the bootstrap loader and the control-plane refresher. If the swap were a
     * {@code clear()} followed by an {@code addAll()}, a request landing between them would match no
     * route and fall through to the default strategy, so a pinned model version, a canary split or a
     * set of weights would silently not apply to it.
     *
     * <p>The reader below asserts it never reaches the default strategy while 300 swaps of a
     * 500-route table run against it. With one volatile reference to an immutable list there is no
     * intermediate state to catch.
     */
    @Test
    void aRouteSwapIsNeverObservedHalfDone() throws Exception {
        RoutingStrategy routed = (req, providers) -> stubProvider("routed");
        RoutingStrategy fellThrough = (req, providers) -> stubProvider("default-strategy");
        RoutingEngine engine = new RoutingEngine(fellThrough, table(routed));

        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        List<LlmProvider> providers = List.of(stubProvider("routed"), stubProvider("default-strategy"));

        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger misses = new java.util.concurrent.atomic.AtomicInteger();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                if (engine.route(request, providers).name().equals("default-strategy")) {
                    misses.incrementAndGet();
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 300; i++) {
                engine.updateRoutes(table(routed));
            }
        } finally {
            stop.set(true);
            reader.join();
        }

        assertThat(misses.get())
                .as("requests that matched no route while the table was being replaced")
                .isZero();
    }

    /** A table big enough that emptying and refilling it is a window a reader can land in. */
    private static List<RoutingEngine.ResolvedRoute> table(RoutingStrategy strategy) {
        List<RoutingEngine.ResolvedRoute> routes = new java.util.ArrayList<>();
        for (int i = 0; i < 499; i++) {
            routes.add(new RoutingEngine.ResolvedRoute(
                    RouteConfig.builder().id("r" + i).modelPattern("never-matches-" + i).build(), strategy));
        }
        routes.add(new RoutingEngine.ResolvedRoute(
                RouteConfig.builder().id("match").modelPattern("gpt-4o").build(), strategy));
        return routes;
    }

    private static LlmProvider stubProvider(String name) {
        return new LlmProvider() {
            @Override public String name() { return name; }
            @Override public boolean supports(ChatRequest request) { return false; }
            @Override public ChatResponse chat(ChatRequest request) { return null; }
            @Override public Iterator<SseChunk> streamChat(ChatRequest request) { return null; }
            @Override public boolean supportsEmbedding(String model) { return false; }
            @Override public EmbeddingResponse embed(EmbeddingRequest request) { return null; }
            @Override public ProviderCapabilities capabilities() { return null; }
        };
    }

    /** Pinning a model rewrites the request; every other field, tools, tool_choice and top_p included, must come through. */
    @Test
    void modelPinning_carriesEveryOtherField() throws Exception {
        RouteConfig config = RouteConfig.builder().modelPattern("gpt-*").pinnedModelVersion("gpt-4o-2024-05-13").build();
        ChatRequest[] received = new ChatRequest[1];
        RoutingStrategy routeStrategy = (req, providers) -> { received[0] = req; return stubProvider("openai"); };
        RoutingEngine engine = new RoutingEngine((req, providers) -> stubProvider("default"),
                List.of(new RoutingEngine.ResolvedRoute(config, routeStrategy)));

        ChatRequest original = fullyPopulated();
        engine.route(original, List.of(stubProvider("openai")));

        assertThat(received[0].getModel()).isEqualTo("gpt-4o-2024-05-13");
        assertOtherFieldsUnchanged(original, received[0], "model");
    }

    // ---- a rewrite carries every other field, by construction ----------------------------

    /** Every ChatRequest field set to a distinct value, so a dropped one is visible. */
    private static ChatRequest fullyPopulated() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hello")))
                .stream(true)
                .maxTokens(321)
                .temperature(0.35)
                .topP(0.91)
                .responseFormat(new ResponseFormat.JsonSchema("person", Map.of("type", "object"), true))
                .metadata(new java.util.HashMap<>(Map.of("workspace_id", "acme")))
                .tools(List.of(ToolDefinition.builder().name("lookup").description("d").parameters(Map.of("type", "object")).build()))
                .toolChoice("auto")
                .build();
    }

    /** Reflects over ChatRequest's fields so a field added later is checked without editing this. */
    private static void assertOtherFieldsUnchanged(ChatRequest original, ChatRequest rewritten, String... changed) throws Exception {
        java.util.Set<String> changedFields = java.util.Set.of(changed);
        int checked = 0;
        for (java.lang.reflect.Field f : ChatRequest.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || changedFields.contains(f.getName())) continue;
            f.setAccessible(true);
            assertThat(f.get(rewritten)).as("field '%s' survives the rewrite", f.getName()).isEqualTo(f.get(original));
            checked++;
        }
        assertThat(checked).as("fields compared").isGreaterThanOrEqualTo(7);
    }
}
