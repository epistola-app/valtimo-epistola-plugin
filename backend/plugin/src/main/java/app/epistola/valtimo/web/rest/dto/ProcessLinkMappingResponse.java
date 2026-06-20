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
 * Response carrying the raw {@code dataMapping} JSONata of a generate-document process link.
 * Empty string when the link or its mapping cannot be resolved — the frontend treats empty as
 * "nothing to suggest". The frontend statically extracts the referenced {@code $doc}/{@code $pv}
 * paths from this expression to guide the override builder.
 */
public record ProcessLinkMappingResponse(String dataMapping) {}
