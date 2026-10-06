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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.StreamStopCheck;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import com.dvarahq.server.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The registered {@link StreamStopCheck}s, as the streaming endpoints ask them. Each stream takes a
 * {@link Probe} before its emit loop starts, while the request is certainly live, and calls
 * {@link Probe#checkpoint()} for every chunk before writing it.
 */
public final class StreamStops {

    private static final Logger log = LoggerFactory.getLogger(StreamStops.class);

    private static final StreamStops NONE = new StreamStops(List.of());

    private final List<StreamStopCheck> checks;

    public StreamStops(List<StreamStopCheck> checks) {
        this.checks = List.copyOf(checks);
    }

    /** The checks registered as beans, in their declared order; none is the usual case. */
    public static StreamStops of(ObjectProvider<StreamStopCheck> checks) {
        List<StreamStopCheck> registered = checks == null ? List.of() : checks.orderedStream().toList();
        return registered.isEmpty() ? NONE : new StreamStops(registered);
    }

    public static StreamStops none() {
        return NONE;
    }

    /**
     * A probe for one stream. Reads the request now, because the emit loop runs after the controller has
     * returned and the container may recycle the request before the stream ends.
     */
    public Probe probe(HttpServletRequest request, String model) {
        if (checks.isEmpty()) {
            return Probe.NOTHING;
        }
        StreamStopCheck.RunningStream stream = new StreamStopCheck.RunningStream(
                (String) request.getAttribute("workspaceId"),
                (String) request.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR),
                (String) request.getAttribute(TraceIdFilter.SESSION_ATTR),
                (String) request.getAttribute(TraceIdFilter.ATTR),
                model,
                request.getRequestURI());
        return new Probe(stream, new ArrayList<>(checks));
    }

    /** The checks for one stream. Used by the stream's own emit thread only. */
    public static final class Probe {

        static final Probe NOTHING = new Probe(null, new ArrayList<>());

        private final StreamStopCheck.RunningStream stream;
        /** A check that threw is removed, so it is logged once and never asked again for this stream. */
        private final List<StreamStopCheck> live;

        private Probe(StreamStopCheck.RunningStream stream, List<StreamStopCheck> live) {
            this.stream = stream;
            this.live = live;
        }

        /**
         * Asks every check, and throws {@link StreamStoppedException} at the first that answers stop.
         * Returns at once when no check is registered.
         */
        public void checkpoint() {
            if (live.isEmpty()) {
                return;
            }
            for (int i = 0; i < live.size(); i++) {
                StreamStopCheck check = live.get(i);
                Optional<StreamStopCheck.Stop> stop;
                try {
                    stop = check.check(stream);
                } catch (RuntimeException e) {
                    // A check that fails must not end the answer it was meant to watch.
                    log.warn("Stream stop check {} failed for trace {} and is skipped for the rest of the stream: {}",
                            check.getClass().getName(), stream.traceId(), e.toString());
                    live.remove(i--);
                    continue;
                }
                if (stop != null && stop.isPresent()) {
                    throw new StreamStoppedException(stop.get());
                }
            }
        }
    }

    /** A stream a {@link StreamStopCheck} ended. Its code is the stop's, so it is recorded under it. */
    public static final class StreamStoppedException extends GatewayException {

        private final transient StreamStopCheck.Stop stop;

        public StreamStoppedException(StreamStopCheck.Stop stop) {
            super(stop.code(), stop.message());
            this.stop = stop;
        }

        public StreamStopCheck.Stop stop() {
            return stop;
        }
    }
}
