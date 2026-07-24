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
package app.epistola.valtimo.service.versioncheck;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemVersionTest {

    @Test
    void finalReleaseOutranksReleaseCandidates() {
        assertThat(SemVersion.parse("1.0.0").orElseThrow()
                .compareTo(SemVersion.parse("1.0.0-RC2").orElseThrow()))
                .isGreaterThan(0);
    }

    @Test
    void releaseCandidatesCompareByIdentifier() {
        assertThat(SemVersion.parse("1.0.0-RC2").orElseThrow()
                .compareTo(SemVersion.parse("1.0.0-RC1").orElseThrow()))
                .isGreaterThan(0);
        assertThat(SemVersion.parse("1.0.0-beta.11").orElseThrow()
                .compareTo(SemVersion.parse("1.0.0-beta.2").orElseThrow()))
                .isGreaterThan(0);
    }

    @Test
    void snapshotSuffixIsIgnoredForComparison() {
        assertThat(SemVersion.parse("1.0.0-RC3-SNAPSHOT"))
                .isEqualTo(SemVersion.parse("1.0.0-RC3"));
        assertThat(SemVersion.parse("1.0.0-SNAPSHOT"))
                .isEqualTo(SemVersion.parse("1.0.0"));
    }

    @Test
    void developmentVersionIsNotSemver() {
        assertThat(SemVersion.parse("development")).isEmpty();
    }
}
