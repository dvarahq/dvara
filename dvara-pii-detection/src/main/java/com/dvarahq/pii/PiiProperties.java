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
package com.dvarahq.pii;

import com.dvarahq.core.pii.PiiAction;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PII settings under {@code dvara.llm-gateway.pii}.
 *
 * <p>Two kinds of setting live here. {@code enabled}, {@code provider}, {@code defaultAction},
 * {@code scanResponses}, {@code stripBeforeCache} and the streaming settings are read by this
 * build. The rest bind here so that every build reads them at the same paths, but nothing in this
 * build acts on them: {@code maxTokensPerWorkspace} and {@code autoDetokenizeResponse} describe a
 * token store this build does not have, and {@link Presidio} and {@link Embedded} configure
 * detector layers this build does not call. Those take effect only when an
 * {@link com.dvarahq.core.pii.AdditionalPiiDetector} declaring the matching layer,
 * {@code "presidio"} or {@code "embedded"}, is registered by another module or by the
 * application. Setting one with no such detector present is warned about at startup, so an
 * operator who configures NER and sees a clean start is told that nothing will call it.
 */
@Data
@ConfigurationProperties("dvara.llm-gateway.pii")
public class PiiProperties {

    private boolean enabled = true;

    /**
     * Which detection layers to use: {@code regex} or {@code presidio}. This records the intent;
     * what activates an NER layer is {@code presidio.endpoint} together with a registered detector.
     * Asking for {@code presidio} with no such detector, or giving an unrecognised value, is warned
     * about at startup.
     */
    private String provider = "regex";

    private PiiAction defaultAction = PiiAction.LOG;

    private boolean scanResponses = true;

    /**
     * Restore tokenized values in a response before it is returned. Off by default. With no
     * tokenization service in this build there is nothing to restore, and turning it on is warned
     * about at startup. Per-workspace override: {@code Workspace.metadata["pii.auto-detokenize-response"]}.
     */
    private boolean autoDetokenizeResponse = false;

    private boolean stripBeforeCache = true;

    /**
     * Ceiling on tokens minted per workspace. Nothing in this build mints tokens, so nothing
     * enforces it; a value other than the default is warned about at startup.
     */
    private int maxTokensPerWorkspace = 50000;


    /** Enable PII scanning on streaming responses. */
    private boolean scanStreamingResponses = true;

    /** Number of characters to buffer before triggering a PII scan during streaming. */
    private int streamingScanWindowSize = 256;

    /** Overlap margin (chars) retained between scan windows to catch boundary-spanning PII. */
    private int streamingOverlapMargin = 64;

    /**
     * How much text a streamed response may hold before it is refused.
     *
     * <p>When the workspace's action can withhold content (BLOCK, REDACT, TOKENIZE, or grounding
     * set to BLOCK), the whole response is held and enforced once at the end, because a PII value
     * has no length bound and grounding cannot be judged on a fragment. Past this bound the stream
     * is refused rather than released half-scanned. Under LOG, and under a guardrail set to FLAG,
     * text streams as it arrives and this does not apply.
     */
    private int streamingMaxHeldCharacters = 1_000_000;

    private Presidio presidio = new Presidio();

    private Embedded embedded = new Embedded();

    /**
     * Settings for an external NER analyzer reached over HTTP. This build calls no analyzer; a
     * detector registered for the {@code "presidio"} layer reads these. With no such detector the
     * endpoint is never contacted, and startup says so.
     */
    @Data
    public static class Presidio {
        /**
         * Analyzer endpoint URL, such as {@code http://presidio-analyzer:3000/analyze}. Setting it
         * with no {@code "presidio"} detector registered is warned about at startup and otherwise
         * does nothing.
         */
        private String endpoint;

        /** Language sent to the analyzer. */
        private String language = "en";

        /** Minimum confidence score threshold. */
        private double scoreThreshold = 0.5;

        /** HTTP timeout in seconds. */
        private int timeoutSeconds = 5;

        /** LRU cache max entries. 0 = disabled. */
        private int cacheMaxSize = 1000;

        /** Cache entry TTL in seconds. */
        private int cacheTtlSeconds = 300;
    }

    /**
     * Settings for an in-process scanner joining the PII pipeline. This build has no such scanner;
     * a detector registered for the {@code "embedded"} layer reads these, and with none present
     * startup says so. Where one exists, its hits are redacted or tokenized like the pattern
     * layer's, unlike the {@code guardrail.embedded.*} settings, which only flag.
     */
    @Data
    public static class Embedded {
        /**
         * Off by default. Enabling it together with {@code guardrail.embedded.enabled} detects the
         * same entity twice, once redacted and once flagged, so pick one. With no embedded scanner
         * registered it is warned about at startup and otherwise does nothing.
         */
        private boolean enabled = false;

        /**
         * Which entity types the scanner looks for, as a list or comma-separated. Blank leaves the
         * choice to the detector. The names are the scanner's own, so the accepted values come from
         * whichever detector provides the {@code "embedded"} layer. Global only: the scan contract
         * carries no workspace.
         */
        private java.util.List<String> filters = new java.util.ArrayList<>();
    }
}