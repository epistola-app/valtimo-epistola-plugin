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
package app.epistola.valtimo.web.rest.dto;

/**
 * Connection health check result for a single Epistola plugin configuration.
 * Includes the server version if the connection was successful.
 */
public record ConnectionStatus(
        String configurationId,
        String configurationTitle,
        String tenantId,
        boolean reachable,
        long latencyMs,
        String errorMessage,
        String serverVersion
) {}
