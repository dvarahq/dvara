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

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fetches an image URL for a provider that takes image bytes only, Gemini and Bedrock (#55), without
 * opening a server-side request forgery path. Off unless an operator turns it on (BR-108-1).
 *
 * <p>The host is resolved once, every address it resolves to is checked, and the connection goes to the
 * checked address, so a DNS answer that changes between the check and the connect (rebinding) cannot
 * redirect the fetch. TLS is verified against the URL's host name. The request is HTTP/1.0 so the body
 * is never chunked and ends at the close. A redirect is followed only through the same checks, at most
 * {@value #MAX_REDIRECTS} times. The size and time limits are enforced while reading, and the bytes are
 * neither cached nor logged (BR-108-3, BR-108-4).
 */
public class ImageFetcher {

    /** The fetcher that fetches nothing: providers keep refusing image URLs, as before #55. */
    public static final ImageFetcher DISABLED = new ImageFetcher(false, 0, Duration.ZERO, Set.of());

    static final int MAX_REDIRECTS = 3;
    private static final int MAX_HEADER_BYTES = 32 * 1024;

    /** An image fetched and ready to inline: its media type and its bytes as base64. */
    public record FetchedImage(String mediaType, String base64) {}

    /** Resolves a host to every address it has; a seam for tests. */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean enabled;
    private final long maxBytes;
    private final Duration timeout;
    private final Set<String> contentTypes;
    private final Resolver resolver;
    private final SSLSocketFactory tls;
    private final boolean allowLoopbackForTests;

    public ImageFetcher(boolean enabled, long maxBytes, Duration timeout, Set<String> contentTypes) {
        this(enabled, maxBytes, timeout, contentTypes, InetAddress::getAllByName,
                (SSLSocketFactory) SSLSocketFactory.getDefault(), false);
    }

    ImageFetcher(boolean enabled, long maxBytes, Duration timeout, Set<String> contentTypes, Resolver resolver,
                 SSLSocketFactory tls, boolean allowLoopbackForTests) {
        this.enabled = enabled;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.contentTypes = contentTypes.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.resolver = resolver;
        this.tls = tls;
        this.allowLoopbackForTests = allowLoopbackForTests;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Fetches {@code url} and returns it ready to inline, or refuses with a 400 naming the reason: the URL
     * (scheme, disallowed address), the fetch (status, time, size) or the content (not an accepted image).
     */
    public FetchedImage fetch(String url) {
        if (!enabled) {
            throw new IllegalStateException("image fetching is off");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        URI uri = parse(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Response response = get(uri, deadline);
            if (response.status >= 300 && response.status < 400 && response.location != null) {
                uri = parse(resolveAgainst(uri, response.location));
                continue;
            }
            if (response.status != 200) {
                throw refused("the image URL answered HTTP " + response.status);
            }
            return new FetchedImage(response.mediaType, Base64.getEncoder().encodeToString(response.body));
        }
        throw refused("the image URL redirected more than " + MAX_REDIRECTS + " times");
    }

    // ── URL and address checks ────────────────────────────────────────────────────────────────

    private URI parse(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException e) {
            throw refused("the image URL is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw refused("an image URL must be https");
        }
        if (uri.getRawUserInfo() != null) {
            throw refused("an image URL must not carry credentials");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw refused("the image URL has no host");
        }
        return uri;
    }

    private static String resolveAgainst(URI base, String location) {
        try {
            return base.resolve(new URI(location.trim())).toString();
        } catch (URISyntaxException e) {
            throw refused("the image URL redirected to an invalid URL");
        }
    }

    /** Every address the host resolves to must be public; one that isn't refuses the URL. */
    private InetAddress checkedAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw refused("the image URL's host does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            throw refused("the image URL's host does not resolve");
        }
        for (InetAddress a : addresses) {
            String reason = disallowed(a);
            if (reason != null && !(allowLoopbackForTests && a.isLoopbackAddress())) {
                throw refused("the image URL's host resolves to " + reason + " address");
            }
        }
        return addresses[0];
    }

    /** Why an address may not be fetched from, or null when it may: private, local, metadata or reserved. */
    static String disallowed(InetAddress a) {
        if (a.isAnyLocalAddress()) return "an unspecified";
        if (a.isLoopbackAddress()) return "a loopback";
        if (a.isLinkLocalAddress()) return "a link-local (cloud metadata)";
        if (a.isSiteLocalAddress()) return "a private";
        if (a.isMulticastAddress()) return "a multicast";
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return disallowedV4(b);
        }
        if (a instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return "a private (unique local)";          // fc00::/7, incl. fd00:ec2::254
            if (first == 0x20 && (b[1] & 0xff) == 0x02) {                             // 2002::/16, 6to4
                return disallowedV4(new byte[] {b[2], b[3], b[4], b[5]});
            }
            if (isPrefix(b, new byte[] {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0})) {  // 64:ff9b::/96
                return disallowedV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
            if (isPrefix(b, new byte[12])) {                                          // ::/96, IPv4-compatible
                return disallowedV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
        }
        return null;
    }

    private static String disallowedV4(byte[] b) {
        int o0 = b[0] & 0xff, o1 = b[1] & 0xff, o2 = b[2] & 0xff;
        if (o0 == 0) return "an unspecified";
        if (o0 == 10 || (o0 == 172 && o1 >= 16 && o1 <= 31) || (o0 == 192 && o1 == 168)) return "a private";
        if (o0 == 127) return "a loopback";
        if (o0 == 169 && o1 == 254) return "a link-local (cloud metadata)";
        if (o0 == 100 && o1 >= 64 && o1 <= 127) return "a shared (carrier-grade NAT)";
        if (o0 == 192 && o1 == 0 && o2 == 0) return "a reserved";
        if (o0 == 198 && (o1 == 18 || o1 == 19)) return "a reserved";
        if (o0 >= 224) return "a multicast or reserved";
        return null;
    }

    private static boolean isPrefix(byte[] address, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (address[i] != prefix[i]) return false;
        }
        return true;
    }

    // ── the fetch ─────────────────────────────────────────────────────────────────────────────

    private record Response(int status, String location, String mediaType, byte[] body) {}

    private Response get(URI uri, long deadline) {
        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        InetAddress address = checkedAddress(host);
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(address, port), remainingMillis(deadline));
            try (SSLSocket socket = (SSLSocket) tls.createSocket(plain, host, port, true)) {
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                params.setEndpointIdentificationAlgorithm("HTTPS");   // the certificate must name the host
                socket.setSSLParameters(params);
                socket.setSoTimeout(remainingMillis(deadline));
                socket.startHandshake();

                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                String hostHeader = uri.getPort() == -1 ? host : host + ":" + port;
                OutputStream out = socket.getOutputStream();
                out.write(("GET " + path + " HTTP/1.0\r\nHost: " + hostHeader + "\r\nUser-Agent: dvara-gateway\r\n"
                        + "Accept: image/*\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return read(socket, deadline);
            }
        } catch (GatewayException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw refused("fetching the image URL timed out");
        } catch (IOException | IllegalArgumentException e) {
            throw refused("fetching the image URL failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private Response read(Socket socket, long deadline) throws IOException {
        InputStream in = socket.getInputStream();
        Map<String, String> headers = new HashMap<>();
        int status = readHead(in, headers, socket, deadline);
        if (status >= 300 && status < 400) {
            return new Response(status, headers.get("location"), null, null);
        }
        if (status != 200) {
            return new Response(status, null, null, null);
        }
        String type = headers.getOrDefault("content-type", "");
        String mediaType = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!contentTypes.contains(mediaType)) {
            throw refused("the image URL is not an accepted image type (" + (mediaType.isEmpty() ? "none" : mediaType) + ")");
        }
        String length = headers.get("content-length");
        if (length != null) {
            try {
                if (Long.parseLong(length.trim()) > maxBytes) {
                    throw refused("the image is larger than " + maxBytes + " bytes");
                }
            } catch (NumberFormatException ignored) {
                // the read below still enforces the limit
            }
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (true) {
            socket.setSoTimeout(remainingMillis(deadline));
            int n = in.read(buf);
            if (n < 0) break;
            if (body.size() + (long) n > maxBytes) {
                throw refused("the image is larger than " + maxBytes + " bytes");
            }
            body.write(buf, 0, n);
        }
        return new Response(200, null, mediaType, body.toByteArray());
    }

    /** Reads the status line and headers, bounded; returns the status and fills lower-cased headers. */
    private int readHead(InputStream in, Map<String, String> headers, Socket socket, long deadline) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            socket.setSoTimeout(remainingMillis(deadline));
            int c = in.read();
            if (c < 0) throw refused("the image URL closed the connection before answering");
            head.write(c);
            if (head.size() > MAX_HEADER_BYTES) throw refused("the image URL's response headers are too large");
            matched = (c == '\r' && (matched == 0 || matched == 2)) || (c == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1 : (c == '\r' ? 1 : 0);
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] statusLine = lines[0].split(" ", 3);
        if (statusLine.length < 2 || !statusLine[0].startsWith("HTTP/")) {
            throw refused("the image URL did not answer HTTP");
        }
        int status;
        try {
            status = Integer.parseInt(statusLine[1]);
        } catch (NumberFormatException e) {
            throw refused("the image URL did not answer HTTP");
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        return status;
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long ms = (deadline - System.nanoTime()) / 1_000_000;
        if (ms <= 0) throw new SocketTimeoutException();
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }

    private static GatewayException refused(String reason) {
        return new GatewayException("INVALID_REQUEST", "Image URL refused: " + reason);
    }
}
