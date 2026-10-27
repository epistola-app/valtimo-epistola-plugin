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
package app.epistola.valtimo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plugin matches a range to read the release a configurator shows, and the server matches it to
 * render: the two must agree, so these follow the contract's "Versions and version ranges".
 */
class VersionRangesTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "1.4.0 ; 1.4.0 ; true",
            "1.4.1 ; 1.4.0 ; false",
            "1.9.0 ; ^1.4.0 ; true",
            "2.0.0 ; ^1.4.0 ; false",
            "1.4.9 ; ~1.4.0 ; true",
            "1.5.0 ; ~1.4.0 ; false",
            "1.5.0 ; >=1.0.0 <2.0.0 ; true",
            "1.5.0 ; 1.0.0 - 2.0.0 ; true",
            "3.1.0 ; ^1.0.0 || ^3.0.0 ; true",
            // A patch release only matches a range that names a pre-release of its own core version.
            "1.3.1-gemeente-x.1 ; ^1.3.0 ; false",
            "1.3.1-gemeente-x.1 ; 1.3.1-gemeente-x.1 ; true",
            "1.3.1-gemeente-x.2 ; >=1.3.1-gemeente-x.1 <1.4.0 ; true",
            // Not a version: never selected, rather than failing the read.
            "latest ; ^1.0.0 ; false",
    })
    void matchesAsTheContractDefines(String version, String range, boolean expected) {
        assertThat(VersionRanges.satisfies(version, VersionRanges.parse(range))).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.4.0", "^1.4.0", "~1.4", ">=1.0.0 <2.0.0", "1.0.0 - 2.0.0", "^1.0.0 || ^3.0.0", "1.x"})
    void acceptsTheContractsGrammar(String range) {
        assertThat(VersionRanges.problem(range)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "[1.0,2.0)", "^", ">=a", "^1.0.0 || nonsense", "^1.0.0 ||"})
    void refusesWhatIsNotARange_ratherThanMatchingNothing(String range) {
        // semver4j reads these as a range nothing satisfies; npm, and so the server, refuses them.
        assertThat(VersionRanges.problem(range)).hasValueSatisfying(problem ->
                assertThat(problem).contains("Invalid version range"));
    }

    @Test
    void refusesARangeLongerThanTheContractAllows() {
        assertThat(VersionRanges.problem("1".repeat(VersionRanges.MAX_LENGTH + 1))).hasValueSatisfying(problem ->
                assertThat(problem).contains("longer than 256"));
    }
}
