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

import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Releases a streaming response's transport from another thread, so a read parked on it returns.
 * What that takes depends on the HTTP client under the {@code RestClient}, and in neither case is
 * the answer "close the response":
 *
 * <ul>
 *   <li><b>Spring's {@code ClientHttpResponse.close()}</b> drains the body before closing, on both
 *       clients, which on a stalled stream waits for the server. Never the handle.</li>
 *   <li><b>JDK HTTP client:</b> closing the body stream itself cancels the subscription and offers
 *       the terminal sentinel, which wakes a blocked read.</li>
 *   <li><b>Apache HttpComponents 5:</b> closing the body stream drains the chunked stream to EOF,
 *       the same wait. The release is HC5's own abort, which discards the connection without
 *       draining. When the body is unencoded that is the body stream's {@code abort()}; when HC5 has
 *       put a decoder on top (gzip, deflate), the abortable stream is out of reach, so the abort is
 *       asked of the entity proxy behind the response instead ({@code EofSensorWatcher.streamAbort}).
 *       Both by reflection: this module does not depend on HC5, and the classes are on the classpath
 *       only where that client is.</li>
 * </ul>
 *
 * <p>Best-effort and never throws; safe from any thread, more than once.
 */
public final class StreamTransport {

    private StreamTransport() {}

    /** Names of the abort method on Apache HttpCore 5's {@code EofSensorInputStream}, tried in order. */
    static final String[] ABORT_METHODS = {"abort", "abortConnection"};
    static final String EOF_SENSOR_WATCHER = "org.apache.hc.core5.http.io.EofSensorWatcher";

    /**
     * @param response the {@code ClientHttpResponse} the body came from, or null; used only to reach
     *                 an abort handle below a decoder on Apache HttpComponents
     * @param body     the body stream the reader wraps
     */
    public static void release(Object response, InputStream body) {
        if (body != null && abortStream(body)) {
            return;
        }
        if (response != null && abortThroughEntityProxy(response)) {
            return;
        }
        if (body != null) {
            try {
                body.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    /** For callers that hold only the stream; equivalent to {@code release(null, body)}. */
    public static void release(InputStream body) {
        release(null, body);
    }

    private static boolean abortStream(InputStream body) {
        for (String name : ABORT_METHODS) {
            try {
                Method abort = body.getClass().getMethod(name);
                abort.invoke(body);
                return true;
            } catch (NoSuchMethodException notThisOne) {
                // try the next name
            } catch (Exception refused) {
                return false;
            }
        }
        return false;
    }

    /**
     * Spring's HC5 response (reached through RestClient's wrapper) holds the {@code ClassicHttpResponse}; its entity is HC5's
     * {@code ResponseEntityProxy}, possibly under a {@code DecompressingEntity}. The proxy is the
     * {@code EofSensorWatcher} whose {@code streamAbort} discards the connection.
     */
    private static boolean abortThroughEntityProxy(Object response) {
        try {
            // RestClient hands its exchange callback a wrapper (DefaultConvertibleClientHttpResponse)
            // around the factory's response; unwrap by its delegate until the HC5 response appears.
            Object hcResponse = null;
            for (int depth = 0; response != null && depth < 4 && hcResponse == null; depth++) {
                hcResponse = readField(response, "httpResponse");
                if (hcResponse == null) {
                    response = readField(response, "delegate");
                }
            }
            if (hcResponse == null) return false;
            Object entity = hcResponse.getClass().getMethod("getEntity").invoke(hcResponse);
            Class<?> watcher = Class.forName(EOF_SENSOR_WATCHER, false, hcResponse.getClass().getClassLoader());
            Method streamAbort = watcher.getMethod("streamAbort", InputStream.class);
            for (int depth = 0; entity != null && depth < 8; depth++) {
                if (watcher.isInstance(entity)) {
                    streamAbort.invoke(entity, new Object[] {null});
                    return true;
                }
                entity = readField(entity, "wrappedEntity");
            }
        } catch (Exception notThatShape) {
            // not HC5, or not the shape we know; the caller falls back to closing the body
        }
        return false;
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException next) {
                // look in the superclass
            }
        }
        return null;
    }
}
