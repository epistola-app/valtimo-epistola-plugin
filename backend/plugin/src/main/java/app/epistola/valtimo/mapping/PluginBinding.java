/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
package app.epistola.valtimo.mapping;

import java.util.Map;

/**
 * The {@code $plugin} binding: what an expression may know about the plugin configuration it runs
 * under, read-only. Only {@code defaultEnvironmentId} for now — the configured default, or
 * {@code ""} when there is none, so {@code $pv.omgeving ?: $plugin.defaultEnvironmentId} always
 * produces a deliberate answer: the case's environment, the default, or none (ADR 0009, second
 * addendum). Never the base URL or the API key.
 */
public final class PluginBinding {

    public static final String NAME = "plugin";

    private PluginBinding() {
    }

    /** The extra binding to give an {@link EvaluationContext}: {@code $plugin}. */
    public static Map<String, Object> of(String defaultEnvironmentId) {
        return Map.of(NAME, Map.of("defaultEnvironmentId", defaultEnvironmentId == null ? "" : defaultEnvironmentId));
    }
}
