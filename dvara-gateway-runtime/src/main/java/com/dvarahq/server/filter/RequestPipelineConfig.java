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
package com.dvarahq.server.filter;

import com.dvarahq.core.filter.ChatFilter;
import com.dvarahq.core.filter.RequestPipeline;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Assembles the {@link RequestPipeline} from all discovered {@link ChatFilter} beans.
 * Every doorway runs its requests through this pipeline.
 */
@Configuration
public class RequestPipelineConfig {

    @Bean
    public RequestPipeline requestPipeline(List<ChatFilter> filters) {
        return new RequestPipeline(filters);
    }
}