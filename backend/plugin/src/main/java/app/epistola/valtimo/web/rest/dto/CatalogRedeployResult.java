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
 * accepted by Epistola's import endpoint, which installs the release it carries as a whole
 * (contract 2.0): {@code catalogId} names the catalog it was installed in
 * ({@code registry/namespace/key}), {@code newlyInstalled} is false when that release was already
 * there, and {@code total} is the number of resources in it.
 * {@code success=false} means the build or import failed — {@code errorMessage}
 * carries the reason, {@code catalogId} is null and {@code total} is zero. {@code httpStatus} is the
 * downstream Epistola status code (when the failure was an HTTP response); the
 * controller maps a downstream 4xx to {@code 422 Unprocessable Entity} (a
 * deterministic data problem the operator must fix) and reserves {@code 502 Bad
 * Gateway} for 5xx / connectivity failures, while still returning this body.
 */
public record CatalogRedeployResult(
        String slug,
        String version,
        boolean success,
        String catalogId,
        boolean newlyInstalled,
        int total,
        String errorMessage,
        Integer httpStatus
) {}
