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
package com.dvarahq.server.filter;

import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateResolutionFilterTest {

    private final PromptTemplateResolver resolver = mock(PromptTemplateResolver.class);
    private final TemplateResolutionFilter filter = new TemplateResolutionFilter(resolver);

    @Test
    void preDispatchHandsTheRequestAndItsWorkspaceToTheResolver() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        ChatRequest resolved = ChatRequest.builder().model("gpt-4o-mini").build();
        when(resolver.resolve(request, "acme")).thenReturn(resolved);

        ChatRequest returned = filter.preDispatch(request, FilterContext.builder().workspaceId("acme").build());

        assertThat(returned).isSameAs(resolved);
        verify(resolver).resolve(request, "acme");
    }

    @Test
    void postDispatchLeavesTheResponseAlone() {
        ChatResponse response = new ChatResponse();

        ChatResponse returned = filter.postDispatch(ChatRequest.builder().model("gpt-4o").build(),
                response, FilterContext.builder().workspaceId("acme").build());

        assertThat(returned).isSameAs(response);
    }
}
