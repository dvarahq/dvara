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
package com.dvarahq.autoconfigure;

import com.dvarahq.providers.ProviderTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The types the factory builds are the library's list, in the same order. There is one list, so a page or a check
 * that reads it from the library offers exactly the types the factory builds.
 */
class ProviderFactoryTypesTest {

    @Test
    void theFactoryBuildsExactlyTheLibrarysTypes() {
        assertThat(ProviderFactory.TYPES).containsExactlyElementsOf(ProviderTypes.ALL);
    }
}
