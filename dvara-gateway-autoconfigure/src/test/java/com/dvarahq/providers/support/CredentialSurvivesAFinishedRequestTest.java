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
package com.dvarahq.providers.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Choosing a credential for a call whose request has already finished.
 *
 * <p>This is the situation a streamed response creates: the controller hands the emitter back, the
 * dispatch unwinds, and only then does the upstream call go out. Every test here finishes the
 * request first — which is exactly what the test this defect slipped past did not do, and why it
 * could never have caught it.
 */
class CredentialSurvivesAFinishedRequestTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        WorkspaceScope.runWith(null, () -> { });
    }

    /** A request as it is during the ordinary part of its life. */
    private static ServletRequestAttributes requestFor(String workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("workspaceId", workspaceId);
        return new ServletRequestAttributes(request);
    }

    @Test
    void askingAFinishedRequestReallyDoesFail() {
        // The control. Without it the rest of this file proves nothing, because it would not be
        // clear there was ever anything to survive.
        ServletRequestAttributes attrs = requestFor("ws-a");
        attrs.requestCompleted();
        RequestContextHolder.setRequestAttributes(attrs);

        assertThatThrownBy(() -> attrs.getAttribute("workspaceId", ServletRequestAttributes.SCOPE_REQUEST))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active anymore");
    }

    @Test
    void theWorkspaceIsStillFoundWhenItWasCarried() {
        // What a stream now does: the workspace is picked up while the request is ordinary and
        // travels with the call, so it is still there when the credential is chosen.
        ServletRequestAttributes attrs = requestFor("ws-a");
        RequestContextHolder.setRequestAttributes(attrs);
        attrs.requestCompleted();

        WorkspaceScope.runWith("ws-a", () ->
                assertThat(CredentialInterceptor.resolveWorkspaceId())
                        .describedAs("streaming used to fail outright here")
                        .isEqualTo("ws-a"));
    }

    @Test
    void anOrdinaryRequestIsUnaffected() {
        // Nothing carried, request alive: it is read from the request exactly as before. This is
        // every non-streaming call, and it must not change.
        RequestContextHolder.setRequestAttributes(requestFor("ws-b"));

        assertThat(CredentialInterceptor.resolveWorkspaceId()).isEqualTo("ws-b");
    }

    @Test
    void whatIsCarriedWinsOverWhatTheRequestSays() {
        // They should never disagree. If they ever do, the carried value is the one chosen
        // deliberately for this call, and the request is the thing that has been known to go stale.
        RequestContextHolder.setRequestAttributes(requestFor("ws-from-request"));

        WorkspaceScope.runWith("ws-carried", () ->
                assertThat(CredentialInterceptor.resolveWorkspaceId()).isEqualTo("ws-carried"));
    }

    @Test
    void acallerWithNoRequestAtAllStillGetsAnAnswer() {
        // A scheduler or a probe genuinely has no workspace, and must still be able to make a call.
        RequestContextHolder.resetRequestAttributes();

        assertThat(CredentialInterceptor.resolveWorkspaceId()).isNull();
    }

    @Test
    void aFinishedRequestWithNothingCarriedDoesNotBringDownTheCall() {
        // The gap case. We would rather it never happened, and it says so in the log, but the call
        // returns without a workspace instead of throwing — strict BYOK then refuses it, which is
        // the right place for that decision to be made.
        ServletRequestAttributes attrs = requestFor("ws-a");
        RequestContextHolder.setRequestAttributes(attrs);
        attrs.requestCompleted();

        assertThat(CredentialInterceptor.resolveWorkspaceId()).isNull();
    }

    @Test
    void recordingWhichCredentialWasUsedNeverFailsTheCall() {
        // Noting who we billed is worth having and is not worth failing a customer's call over.
        ServletRequestAttributes attrs = requestFor("ws-a");
        RequestContextHolder.setRequestAttributes(attrs);
        attrs.requestCompleted();

        CredentialInterceptor.recordFingerprint("sk-not-a-real-key");
        assertThat(CredentialInterceptor.resolveFingerprint()).isNull();
    }
}
