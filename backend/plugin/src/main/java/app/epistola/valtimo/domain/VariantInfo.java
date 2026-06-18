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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Information about an Epistola template variant.
 *
 * @param id         The unique identifier of the variant
 * @param templateId The ID of the template this variant belongs to
 * @param name       The display name of the variant
 * @param attributes Key-value attributes for categorizing/selecting the variant
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VariantInfo(
        String id,
        String templateId,
        String name,
        Map<String, String> attributes
) {
}
