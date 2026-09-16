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
package com.dvarahq.core.pii;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiScanResultTest {

    @Test
    void empty_hasNoPii() {
        assertThat(PiiScanResult.EMPTY.hasPii()).isFalse();
        assertThat(PiiScanResult.EMPTY.entityCount()).isZero();
    }

    @Test
    void hasPii_trueWhenEntitiesPresent() {
        PiiEntity entity = new PiiEntity(PiiEntityType.EMAIL, "test@example.com", 0, 16, "email", 0.99);
        PiiScanResult result = new PiiScanResult(List.of(entity), "test@example.com");

        assertThat(result.hasPii()).isTrue();
        assertThat(result.entityCount()).isEqualTo(1);
    }

    @Test
    void entityCount_matchesListSize() {
        PiiEntity e1 = new PiiEntity(PiiEntityType.EMAIL, "a@b.com", 0, 7, "email", 0.9);
        PiiEntity e2 = new PiiEntity(PiiEntityType.PHONE_NUMBER, "555-1234", 10, 18, "phone", 0.8);
        PiiScanResult result = new PiiScanResult(List.of(e1, e2), "a@b.com 555-1234");

        assertThat(result.entityCount()).isEqualTo(2);
    }

    @Test
    void emptyEntityList_noPii() {
        PiiScanResult result = new PiiScanResult(List.of(), "no pii here");

        assertThat(result.hasPii()).isFalse();
        assertThat(result.entityCount()).isZero();
    }
}