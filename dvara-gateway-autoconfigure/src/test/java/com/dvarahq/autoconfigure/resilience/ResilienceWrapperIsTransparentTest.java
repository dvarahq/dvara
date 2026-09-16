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
package com.dvarahq.autoconfigure.resilience;

import com.dvarahq.core.provider.LlmProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilience wrapper forwards every method on {@link LlmProvider}.
 *
 * <p>The auto-configuration's bean post-processor wraps every provider bean except the mock, so a
 * method the wrapper does not forward is a method no provider in a running gateway has, and most of
 * the interface's defaults throw. The reflection check fails on the next method added to the
 * interface, which a behavioural test would not think to cover.</p>
 */
class ResilienceWrapperIsTransparentTest {

    @Test
    void everyMethodOnTheInterfaceIsForwarded() {
        Set<String> forwarded = Arrays.stream(ResilientLlmProvider.class.getDeclaredMethods())
                .map(ResilienceWrapperIsTransparentTest::signature)
                .collect(Collectors.toSet());

        List<String> missing = Arrays.stream(LlmProvider.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .map(ResilienceWrapperIsTransparentTest::signature)
                .filter(s -> !forwarded.contains(s))
                .sorted()
                .toList();

        assertThat(missing)
                .as("LlmProvider methods ResilientLlmProvider does not forward; each one is a "
                        + "capability the wrapper removes from every provider")
                .isEmpty();
    }

    @Test
    void theBatchSurfaceReachesTheDelegate() {
        RecordingProvider delegate = new RecordingProvider();
        ResilientLlmProvider resilient = new ResilientLlmProvider(
                delegate,
                CircuitBreaker.ofDefaults("batch-transparency"),
                Retry.ofDefaults("batch-transparency"),
                TimeLimiter.ofDefaults("batch-transparency"),
                TimeLimiter.ofDefaults("batch-transparency-stream"));

        assertThat(resilient.uploadFile("{}".getBytes(StandardCharsets.UTF_8), "in.jsonl", "batch"))
                .isEqualTo("uploaded:in.jsonl");
        assertThat(resilient.createBatch("{\"input_file_id\":\"file-1\"}")).isEqualTo("created");
        assertThat(resilient.getBatch("batch-1")).isEqualTo("got:batch-1");
        assertThat(resilient.cancelBatch("batch-1")).isEqualTo("cancelled:batch-1");
        assertThat(new String(resilient.getFileContent("file-1"), StandardCharsets.UTF_8))
                .isEqualTo("content:file-1");

        assertThat(delegate.calls)
                .containsExactly("uploadFile", "createBatch", "getBatch", "cancelBatch", "getFileContent");
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes());
    }

    /** A batch-capable provider that records what it was asked, so a dropped call is visible. */
    private static final class RecordingProvider implements LlmProvider {
        private final List<String> calls = new java.util.ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public boolean supports(com.dvarahq.core.model.ChatRequest request) {
            return true;
        }

        @Override
        public com.dvarahq.core.model.ChatResponse chat(com.dvarahq.core.model.ChatRequest request) {
            throw new UnsupportedOperationException("not part of this test");
        }

        @Override
        public com.dvarahq.core.provider.ProviderCapabilities capabilities() {
            return new com.dvarahq.core.provider.ProviderCapabilities(
                    true, false, false, false, false, true, false, 128_000);
        }

        @Override
        public String uploadFile(byte[] content, String filename, String purpose) {
            calls.add("uploadFile");
            return "uploaded:" + filename;
        }

        @Override
        public String createBatch(String requestJson) {
            calls.add("createBatch");
            return "created";
        }

        @Override
        public String getBatch(String batchId) {
            calls.add("getBatch");
            return "got:" + batchId;
        }

        @Override
        public String cancelBatch(String batchId) {
            calls.add("cancelBatch");
            return "cancelled:" + batchId;
        }

        @Override
        public byte[] getFileContent(String fileId) {
            calls.add("getFileContent");
            return ("content:" + fileId).getBytes(StandardCharsets.UTF_8);
        }
    }
}
