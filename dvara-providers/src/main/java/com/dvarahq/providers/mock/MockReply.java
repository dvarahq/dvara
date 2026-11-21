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
package com.dvarahq.providers.mock;

/**
 * What a matcher answers: the response text and, when a scenario sets them, the usage to report for it
 * (#56). A {@code null} token count means "estimate it", as the mock does for every other response.
 *
 * @param text             the response body
 * @param promptTokens     prompt tokens to report as given, or {@code null} to estimate
 * @param completionTokens completion tokens to report as given, or {@code null} to estimate
 */
public record MockReply(String text, Integer promptTokens, Integer completionTokens) {

    /** A reply whose usage the mock estimates. */
    public static MockReply text(String text) {
        return new MockReply(text, null, null);
    }
}
