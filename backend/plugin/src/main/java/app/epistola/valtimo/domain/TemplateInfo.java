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
package app.epistola.valtimo.domain;

/**
 * Basic information about an Epistola template.
 *
 * @param id          The unique identifier of the template
 * @param name        The display name of the template
 * @param description Optional description of the template
 * @param catalogId   The slug identifier of the catalog this template belongs to
 * @param catalogName The display name of the catalog this template belongs to
 */
public record TemplateInfo(
        String id,
        String name,
        String description,
        String catalogId,
        String catalogName
) {
}
