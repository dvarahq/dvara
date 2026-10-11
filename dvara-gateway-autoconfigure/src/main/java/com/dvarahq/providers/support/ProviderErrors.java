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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dvarahq.core.exception.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A provider's own reason for refusing a call: for the gateway's log and, on a {@code 400}, for the caller.
 *
 * <p>An operator must be able to tell a bad image URL from a bad parameter from a content refusal, so
 * the provider's {@code message} and {@code code} are logged, on one line and capped, at WARN.
 *
 * <p>The caller gets the gateway's own short message. When the provider answered {@code 400}, the
 * request itself was wrong and only the provider can say how ("use max_completion_tokens"), so its
 * {@code code} and {@code message} are added, on one line and capped. Nothing else of the body is
 * returned, since a body can echo the request. No other status carries the reason: a {@code 401} or
 * {@code 403} is about the gateway's own credential, not the caller's request.
 */
public final class ProviderErrors {

    private static final Logger log = LoggerFactory.getLogger(ProviderErrors.class);

    /** How much of a body is read, and how much of the reason is logged. */
    static final int READ_LIMIT = 8192;
    static final int REASON_LIMIT = 300;

    // The common shapes: OpenAI and its compatibles {"error":{"message","code","type"}}, Anthropic
    // {"error":{"type","message"}}, Gemini {"error":{"code","message","status"}}, Bedrock {"message"}.
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CODE = field("code");
    private static final Pattern TYPE = field("type");
    private static final Pattern STATUS = field("status");
    /** A provider key quoted back in a message, such as {@code sk-...}. */
    private static final Pattern SECRET = Pattern.compile("\\bsk-[A-Za-z0-9_*\\-]{8,}");

    private ProviderErrors() {
    }

    private static Pattern field(String name) {
        return Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    }

    /** Logs why {@code provider} refused the call. Never throws: a body that can't be read logs as such. */
    public static void logRefusal(String provider, ClientHttpResponse res) {
        int status;
        try {
            status = res.getStatusCode().value();
        } catch (IOException | RuntimeException e) {
            status = -1;
        }
        log.warn("{} refused the call with {}: {}", provider, status, reason(res));
    }

    /**
     * Logs why {@code provider} refused the call, as {@link #logRefusal} does, and returns the error for the
     * caller: {@code what}, the status and its hint, then {@code detail} (may be empty). On a {@code 400} the
     * provider's own reason follows, when its body names one. Reads the body once, and never throws.
     */
    public static GatewayException refusal(String provider, ClientHttpResponse res, String what, String detail) {
        int status;
        try {
            status = res.getStatusCode().value();
        } catch (IOException | RuntimeException e) {
            status = 502;
        }
        String body = body(res);
        log.warn("{} refused the call with {}: {}", provider, status, body == null ? "(no readable body)" : reason(body));
        String message = what + " " + status + detail + GatewayException.describeHttpStatus(status);
        String forCaller = status == 400 && body != null ? callerReason(body) : null;
        return GatewayException.upstream(status, forCaller == null ? message : message + ": " + forCaller);
    }

    /** {@link #refusal(String, ClientHttpResponse, String, String)} with nothing after the status. */
    public static GatewayException refusal(String provider, ClientHttpResponse res, String what) {
        return refusal(provider, res, what, "");
    }

    /**
     * What of a refusal the caller may see: the provider's {@code code} and {@code message}, or nothing when
     * the body has no {@code message}. Never the raw body. Anything shaped like an API key is masked.
     */
    static String callerReason(String body) {
        Matcher m = MESSAGE.matcher(body);
        if (!m.find()) {
            return null;
        }
        String message = unescape(m.group(1));
        String code = code(body);
        String reason = oneLine(code == null ? message : code + ": " + message);
        return SECRET.matcher(reason).replaceAll("***");
    }

    private static String body(ClientHttpResponse res) {
        try (InputStream in = res.getBody()) {
            return new String(in.readNBytes(READ_LIMIT), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The provider's {@code code} and {@code message}, or the start of the body; one line, capped. */
    static String reason(ClientHttpResponse res) {
        String body = body(res);
        return body == null ? "(no readable body)" : reason(body);
    }

    static String reason(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        Matcher m = MESSAGE.matcher(body);
        String message = m.find() ? unescape(m.group(1)) : null;
        String code = code(body);
        String reason = message == null ? body : (code == null ? message : code + ": " + message);
        return oneLine(reason);
    }

    /**
     * The most specific label: {@code code} (OpenAI's {@code invalid_image_url}), else a {@code type}
     * other than Anthropic's top-level {@code "error"}, else {@code status} (Gemini).
     */
    private static String code(String body) {
        Matcher c = CODE.matcher(body);
        if (c.find()) return unescape(c.group(1));
        Matcher t = TYPE.matcher(body);
        while (t.find()) {
            if (!"error".equals(t.group(1))) return unescape(t.group(1));
        }
        Matcher s = STATUS.matcher(body);
        return s.find() ? unescape(s.group(1)) : null;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
    }

    private static String oneLine(String s) {
        String flat = s.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").strip();
        return flat.length() > REASON_LIMIT ? flat.substring(0, REASON_LIMIT) + "…" : flat;
    }
}
