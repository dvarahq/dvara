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

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC exceptions raised before a controller is selected must carry their own HTTP status,
 * not the catch-all's 500.
 *
 * <p>These tests go through the DispatcherServlet on purpose. {@link GlobalExceptionHandlerTest}
 * calls the advice's methods directly, which proves what a handler returns when it is called but
 * cannot show whether Spring calls it at all. Driving real requests at a real handler mapping lets
 * Spring raise the exception the way production does.
 */
class SpringMvcExceptionStatusTest {

    @RestController
    static class Probe {
        @GetMapping("/probe")
        String read() { return "ok"; }

        @PostMapping(value = "/probe/write", consumes = MediaType.APPLICATION_JSON_VALUE)
        String write(@RequestParam("required") String required) { return required; }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void wrongMethodIs405NotAnInternalError() throws Exception {
        mvc.perform(post("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("method_not_allowed"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                // The message names the method the caller actually used, so the log line and the
                // response agree about what happened.
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("POST")));
    }

    @Test
    void the405CarriesAllow() throws Exception {
        // RFC 9110 makes Allow required on a 405, and it is the only part of the response that tells
        // the caller what to do instead. Asserted separately because a status-only check would not
        // notice it missing.
        mvc.perform(post("/probe"))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mvc.perform(post("/probe/write").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));
    }

    @Test
    void missingRequiredParameterIs400() throws Exception {
        mvc.perform(post("/probe/write").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("missing_parameter"))
                // Naming the parameter is the entire value of a 400 here.
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("required")));
    }

    @Test
    void theBodyStaysTheGatewaysOwnErrorShape() throws Exception {
        // Deliberately not Spring's ProblemDetail. Extending ResponseEntityExceptionHandler would
        // give this plane two different error shapes depending on which exception fired.
        mvc.perform(post("/probe"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.type").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    void aCorrectRequestIsUntouched() throws Exception {
        // An over-eager handler would turn working requests into 4xx, which a suite of negative
        // cases alone would not notice.
        mvc.perform(get("/probe")).andExpect(status().isOk());
        mvc.perform(post("/probe/write").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").param("required", "value"))
                .andExpect(status().isOk());
    }
}