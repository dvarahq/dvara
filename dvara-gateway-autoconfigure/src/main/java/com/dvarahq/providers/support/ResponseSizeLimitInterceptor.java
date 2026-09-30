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

import com.dvarahq.core.exception.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

/**
 * Bounds how many bytes of a provider's response the gateway reads.
 *
 * <p>Every other limit is on the request. A provider that answers with a pathological body would
 * otherwise be buffered and parsed whole, which is the direction an out-of-memory arrives from. The
 * limit holds before anything is parsed: a stated {@code Content-Length} over the limit is refused
 * before the body is touched, a whole body is read up to the limit before it is handed on, and a
 * streamed one is counted as it is read and refused at the first read past the limit.</p>
 *
 * <p>Two limits, because the two kinds of response differ by an order of magnitude in normal use.
 * A streamed response ({@code text/event-stream}, NDJSON or an AWS event stream) carries its framing
 * around every token and is read piece by piece, so it gets its own, larger limit. A file download
 * ({@code application/octet-stream}, such as a batch's results) is counted against that limit too,
 * rather than held in memory. A limit of zero or less turns that limit off.</p>
 *
 * <p>The refusal is a {@link GatewayException} with code {@code PROVIDER_RESPONSE_TOO_LARGE}. It is
 * not an {@link IOException}: the HTTP client would report that as a transport failure, which is
 * retried and counts against the provider's circuit, and a response that is too large is neither.</p>
 */
public class ResponseSizeLimitInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ResponseSizeLimitInterceptor.class);

    public static final String CODE = "PROVIDER_RESPONSE_TOO_LARGE";

    private static final Set<String> STREAM_TYPES = Set.of(
            "text/event-stream", "application/x-ndjson", "application/jsonl",
            "application/vnd.amazon.eventstream", "application/octet-stream");

    private final String provider;
    private final long maxBodyBytes;
    private final long maxStreamBytes;

    public ResponseSizeLimitInterceptor(String provider, long maxBodyBytes, long maxStreamBytes) {
        this.provider = provider;
        this.maxBodyBytes = maxBodyBytes;
        this.maxStreamBytes = maxStreamBytes;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        boolean stream = isStream(response.getHeaders().getContentType());
        long limit = stream ? maxStreamBytes : maxBodyBytes;
        if (limit <= 0) {
            return response;
        }
        long stated = response.getHeaders().getContentLength();
        if (stated > limit) {
            // Released, not closed: closing drains the body first, which is the read being refused.
            StreamTransport.release(response, response.getBody());
            throw tooLarge(stream, limit);
        }
        if (stream) {
            return new Limited(response, new LimitedBody(response, response.getBody(), limit,
                    () -> tooLarge(true, limit)));
        }
        // A whole body is read here, up to one byte past the limit, before anything parses it. The
        // refusal then comes from this call, where it reaches the caller as it is: thrown from inside
        // a parser, it would arrive wrapped as a failure to read the response, and be retried.
        InputStream in = response.getBody();
        byte[] read = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
        if (read.length > limit) {
            StreamTransport.release(response, in);
            throw tooLarge(false, limit);
        }
        return new Limited(response, new java.io.ByteArrayInputStream(read));
    }

    private static boolean isStream(MediaType type) {
        if (type == null) {
            return false;
        }
        return STREAM_TYPES.contains((type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT));
    }

    private GatewayException tooLarge(boolean stream, long limit) {
        log.warn("Provider [{}] sent a {} larger than the {}-byte limit; the response was refused",
                provider, stream ? "streamed response" : "response body", limit);
        return new GatewayException(CODE, "Provider " + provider + " sent a "
                + (stream ? "streamed response" : "response") + " larger than the gateway's limit of "
                + limit + " bytes, so it was refused.");
    }

    /**
     * The response with its body replaced by a limited or already-read one. The field is named
     * {@code delegate} because {@link StreamTransport} looks for that name to reach the client's own
     * response when it has to abort a stalled stream.
     */
    private static final class Limited implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final InputStream body;

        Limited(ClientHttpResponse delegate, InputStream body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return body;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Counts the bytes read and refuses the first read past the limit. Public, with a public
     * {@code abort()}, so that {@link StreamTransport} can release a parked read through this stream
     * by reflection as it would through the client's own.
     */
    public static final class LimitedBody extends FilterInputStream {

        private final ClientHttpResponse response;
        private final long limit;
        private final java.util.function.Supplier<GatewayException> refusal;
        private long count;

        LimitedBody(ClientHttpResponse response, InputStream in, long limit,
                    java.util.function.Supplier<GatewayException> refusal) {
            super(in);
            this.response = response;
            this.limit = limit;
            this.refusal = refusal;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                counted(n);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) {
                counted(skipped);
            }
            return skipped;
        }

        private void counted(long n) {
            count += n;
            if (count > limit) {
                StreamTransport.release(response, in);
                throw refusal.get();
            }
        }

        public void abort() {
            StreamTransport.release(response, in);
        }
    }
}
