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
package com.dvarahq.core.cost;

import java.math.BigDecimal;

/**
 * What one million tokens of a model cost, in US dollars, as the gateway would charge them.
 *
 * @param inputPerMillion  the price of input tokens
 * @param outputPerMillion the price of the output a request may use up, its {@code max_tokens}. For a model whose
 *                         reasoning tokens cost more than its visible output, the higher of the two, since the limit
 *                         covers both
 */
public record TokenPrices(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
}
