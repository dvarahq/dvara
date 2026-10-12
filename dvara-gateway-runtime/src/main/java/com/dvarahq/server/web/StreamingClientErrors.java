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
package com.dvarahq.server.web;

import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationStrategy;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets a client that accepts only {@code text/event-stream} read a JSON answer.
 *
 * <p>The provider SDKs, and agents built on them, send {@code Accept: text/event-stream} with a
 * streamed request. When the gateway refuses such a request before the stream opens (a policy, a guardrail, a
 * field it does not support), the refusal is a JSON error. Without this, the JSON could not be written for that
 * {@code Accept}, and the client got an empty 500 instead of the refusal. The SDKs read an error from the body of
 * any non-2xx answer whatever its content type, so the refusal is sent as JSON with its own status.</p>
 *
 * <p>Only a request that accepts {@code text/event-stream} gets {@code application/json} added to what it accepts,
 * after everything it named. Any other {@code Accept} is negotiated as before.</p>
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class StreamingClientErrors implements WebMvcConfigurer {

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.strategies(List.of(new JsonForStreamingClients()));
    }

    /** The {@code Accept} header's types, with JSON added last when they include an event stream. */
    static final class JsonForStreamingClients implements ContentNegotiationStrategy {
        private final HeaderContentNegotiationStrategy header = new HeaderContentNegotiationStrategy();

        @Override
        public List<MediaType> resolveMediaTypes(NativeWebRequest request) throws HttpMediaTypeNotAcceptableException {
            List<MediaType> types = header.resolveMediaTypes(request);
            boolean eventStream = types.stream().anyMatch(t -> t.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                    && !t.isWildcardType());
            boolean json = types.stream().anyMatch(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON));
            if (!eventStream || json) {
                return types;
            }
            List<MediaType> out = new ArrayList<>(types);
            out.add(MediaType.APPLICATION_JSON);
            return out;
        }
    }
}
