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
 * Outcome of a manual single-catalog redeploy from the admin page.
 *
 * <p>{@code success=true} means the catalog ZIP was built from the classpath and
 * accepted by Epistola's import endpoint; {@code installed} / {@code updated} /
 * {@code failed} / {@code total} are the per-resource counts Epistola reported.
 * {@code success=false} means the build or import failed — {@code errorMessage}
 * carries the reason and the resource counts are zero. The controller maps a
 * failed redeploy to HTTP 502 so standard client error handling applies while
 * still returning this body.
 */
public record CatalogRedeployResult(
        String slug,
        String version,
        boolean success,
        String catalogKey,
        int installed,
        int updated,
        int failed,
        int total,
        String errorMessage
) {}
