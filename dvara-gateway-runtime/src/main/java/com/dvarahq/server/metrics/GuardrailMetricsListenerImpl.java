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
package com.dvarahq.server.metrics;

import com.dvarahq.core.guardrail.GuardrailMetricsListener;
import org.springframework.stereotype.Component;

/**
 * Records guardrail events as gateway metrics. Not {@code @Primary}: {@code GuardrailScanService}
 * collects every {@code GuardrailMetricsListener} and calls each one, so a listener registered by
 * another module joins this one rather than replacing it.
 */
@Component
public class GuardrailMetricsListenerImpl implements GuardrailMetricsListener {

    private final GatewayMetrics metrics;

    public GuardrailMetricsListenerImpl(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onBlocked(String workspaceId, String category) {
        metrics.recordGuardrailBlocked(workspaceId, category);
    }

    @Override
    public void onFlagged(String workspaceId, String category) {
        metrics.recordGuardrailFlagged(workspaceId, category);
    }

    @Override
    public void onMlDetection(String provider, String category, String action) {
        metrics.recordMlGuardrail(provider, category, action);
    }

    @Override
    public void onClassifierScan(String provider, boolean flagged) {
        metrics.recordMlClassifierScan(provider, flagged);
    }

    @Override
    public void onPluginDetection(String plugin, String category, String action) {
        metrics.recordPluginGuardrail(plugin, category, action);
    }
}