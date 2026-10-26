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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogRefTest {

    @Test
    void readsAFullReference() {
        CatalogRef ref = CatalogRef.parse("exchange.epistola.app/acme/letters");

        assertThat(ref).isEqualTo(new CatalogRef("exchange.epistola.app", "acme", "letters"));
        assertThat(ref.isQualified()).isTrue();
        assertThat(ref).hasToString("exchange.epistola.app/acme/letters");
    }

    @Test
    void readsANamespaceAndKey_inTheDefaultRegistry() {
        CatalogRef ref = CatalogRef.parse("acme/letters");

        assertThat(ref).isEqualTo(new CatalogRef(null, "acme", "letters"));
        assertThat(ref.isQualified()).isTrue();
        assertThat(ref).hasToString("acme/letters");
    }

    @Test
    void readsAKeySavedAgainstContract1_asUnqualified() {
        CatalogRef ref = CatalogRef.parse("letters");

        assertThat(ref).isEqualTo(new CatalogRef(null, null, "letters"));
        assertThat(ref.isQualified()).isFalse();
        assertThat(ref).hasToString("letters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a/b/c/d", "/letters", "acme/", "a//b"})
    void refusesWhatIsNotAReference(String value) {
        assertThatThrownBy(() -> CatalogRef.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnqualifiedReference_matchesTheKeyInAnyNamespace() {
        CatalogRef ref = CatalogRef.parse("letters");

        assertThat(ref.matches("local", "valtimo", "letters")).isTrue();
        assertThat(ref.matches("exchange.epistola.app", "acme", "letters")).isTrue();
        assertThat(ref.matches("local", "valtimo", "other")).isFalse();
    }

    @Test
    void aQualifiedReference_matchesOnlyItsNamespace_andItsRegistryWhenNamed() {
        assertThat(CatalogRef.parse("acme/letters").matches("exchange.epistola.app", "acme", "letters")).isTrue();
        assertThat(CatalogRef.parse("acme/letters").matches("local", "other", "letters")).isFalse();
        assertThat(CatalogRef.parse("local/acme/letters").matches("exchange.epistola.app", "acme", "letters"))
                .isFalse();
        assertThat(CatalogRef.parse("local/acme/letters").matches("local", "acme", "letters")).isTrue();
    }
}
