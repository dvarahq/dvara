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

import com.dvarahq.core.call.InternalCall;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The request an internal call runs as. The governed path keeps what it learns about a call on the
 * request (the provider that served it, the credential, the route, the policy decision) and reads it
 * back to write the usage and cost rows, so an internal call needs one of its own. Using the request
 * of the caller that caused it would record the internal call's provider and model on the caller's
 * request, and the caller's on the internal call's rows.
 *
 * <p>It holds attributes and nothing else: no headers, no body, no client address. It is stamped with
 * the workspace and key the call is billed to, as the API-key filter stamps a caller's request.
 */
final class InternalCallRequest {

    private InternalCallRequest() {
    }

    static HttpServletRequest of(InternalCall call) {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        attributes.put(ApiKeyAuthFilter.WORKSPACE_ID_ATTR, call.workspaceId());
        attributes.put(ApiKeyAuthFilter.API_KEY_ID_ATTR, call.apiKeyId());
        attributes.put(ApiKeyAuthFilter.API_KEY_ATTR, call.apiKeyId());
        String uri = "/internal/" + call.kind();
        return (HttpServletRequest) Proxy.newProxyInstance(
                InternalCallRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "setAttribute" -> {
                        if (args[1] == null) {
                            attributes.remove((String) args[0]);
                        } else {
                            attributes.put((String) args[0], args[1]);
                        }
                        yield null;
                    }
                    case "removeAttribute" -> attributes.remove((String) args[0]);
                    case "getAttributeNames" -> Collections.enumeration(attributes.keySet());
                    case "getRequestURI", "getServletPath" -> uri;
                    case "getRequestURL" -> new StringBuffer(uri);
                    case "getMethod" -> "POST";
                    case "getContextPath" -> "";
                    case "getCharacterEncoding" -> "UTF-8";
                    case "toString" -> "InternalCallRequest[" + uri + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> empty(method.getReturnType());
                });
    }

    /** What a request with no headers, parameters or client says when asked for one. */
    private static Object empty(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return -1;
        }
        if (type == long.class) {
            return -1L;
        }
        if (Enumeration.class.isAssignableFrom(type)) {
            return Collections.emptyEnumeration();
        }
        if (Map.class.isAssignableFrom(type)) {
            return Map.of();
        }
        if (Collection.class.isAssignableFrom(type)) {
            return java.util.List.of();
        }
        return null;
    }
}
