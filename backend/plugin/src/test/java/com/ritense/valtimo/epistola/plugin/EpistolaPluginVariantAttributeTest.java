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
package com.ritense.valtimo.epistola.plugin;

import app.epistola.client.model.VariantSelectionAttribute;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract 1.4 accepted another catalog's attribute as a dotted key ({@code system.locale}); 2.0
 * refuses it and takes the catalog in a member of its own. Process links saved then keep working.
 */
class EpistolaPluginVariantAttributeTest {

    @Test
    void aPlainKey_isTheTemplatesOwnCatalogs() {
        VariantSelectionAttribute attribute = EpistolaPlugin.variantSelectionAttribute("taal", "nl", true);

        assertThat(attribute.getKey()).isEqualTo("taal");
        assertThat(attribute.getCatalogKey()).isNull();
        assertThat(attribute.getValue()).isEqualTo("nl");
        assertThat(attribute.getRequired()).isTrue();
    }

    @Test
    void aDottedKey_namesItsCatalogApart() {
        VariantSelectionAttribute attribute = EpistolaPlugin.variantSelectionAttribute("system.locale", "nl", false);

        assertThat(attribute.getCatalogKey()).isEqualTo("system");
        assertThat(attribute.getKey()).isEqualTo("locale");
    }
}
