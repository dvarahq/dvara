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
package com.dvarahq.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A streamed request must record which provider served it.
 *
 * <p>{@code ProviderDispatcher.setProviderAttribute} resolves the request through
 * {@code RequestContextHolder}, a ThreadLocal, and the streaming controllers dispatch on a virtual
 * thread where that ThreadLocal is unset. {@code ChatExecutionService.withRequestContext} carries
 * the request context across, so these tests assert across a thread boundary: on the test thread the
 * ThreadLocal is already set and the wrapper would look unnecessary.
 */
class StreamingRequestContextPropagationTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void attributesSetOnAnotherThreadReachTheOriginalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // Wrapping happens on the request thread — that is when the attributes are captured.
        Runnable body = ChatExecutionService.withRequestContext(() ->
                // Stand-in for what ProviderDispatcher does deep inside the dispatch.
                ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                        .getRequest().setAttribute("gateway.provider", "openai"));

        Thread t = Thread.ofVirtual().start(body);
        t.join();

        assertThat(request.getAttribute("gateway.provider"))
                .as("the provider recorded during a streamed dispatch must reach the request that "
                        + "persistTokenUsage/calculateCost later read")
                .isEqualTo("openai");
    }

    /** Without the wrapper the ThreadLocal is unset on the new thread. */
    @Test
    void withoutTheWrapperTheContextIsMissing_whichIsWhatThisFixes() throws Exception {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));

        AtomicReference<Object> seen = new AtomicReference<>("sentinel");
        Thread t = Thread.ofVirtual().start(() -> seen.set(RequestContextHolder.getRequestAttributes()));
        t.join();

        assertThat(seen.get())
                .as("a plain virtual thread sees no request context, and setProviderAttribute's "
                        + "null guard would turn that into silence")
                .isNull();
    }

    /** No servlet request (internal call, scheduler, test) must stay a plain pass-through. */
    @Test
    void withNoRequestContextTheBodyStillRuns() throws Exception {
        RequestContextHolder.resetRequestAttributes();

        AtomicReference<Boolean> ran = new AtomicReference<>(false);
        Thread t = Thread.ofVirtual().start(ChatExecutionService.withRequestContext(() -> ran.set(true)));
        t.join();

        assertThat(ran.get()).isTrue();
    }

    /** The binding must not outlive the body, or it leaks onto whatever runs next. */
    @Test
    void contextIsUnboundAfterTheBodyCompletes() throws Exception {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));

        AtomicReference<Object> after = new AtomicReference<>("sentinel");
        Thread t = Thread.ofVirtual().start(ChatExecutionService.withRequestContext(() -> {
            /* body */
        }));
        t.join();

        Thread probe = Thread.ofVirtual().start(() -> after.set(RequestContextHolder.getRequestAttributes()));
        probe.join();
        assertThat(after.get()).isNull();
    }
}