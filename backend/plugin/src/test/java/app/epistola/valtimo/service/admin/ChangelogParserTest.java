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
package app.epistola.valtimo.service.admin;

import app.epistola.valtimo.web.rest.dto.ChangelogRelease;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogParserTest {

    @Test
    void parsesReleasesSectionsAndItemsInOrder() {
        String md = """
                # Changelog

                All notable changes are documented here.

                ## [Unreleased]

                ### Added

                - First unreleased thing
                - Second thing

                ## [0.8.0] - 2026-05-08

                ### Added

                - A shipped feature

                ### Changed (breaking)

                - A breaking change
                """;

        List<ChangelogRelease> releases = ChangelogParser.parse(md);

        assertThat(releases).hasSize(2);

        ChangelogRelease unreleased = releases.get(0);
        assertThat(unreleased.version()).isEqualTo("Unreleased");
        assertThat(unreleased.date()).isNull();
        assertThat(unreleased.sections()).hasSize(1);
        assertThat(unreleased.sections().get(0).title()).isEqualTo("Added");
        assertThat(unreleased.sections().get(0).items())
                .containsExactly("First unreleased thing", "Second thing");

        ChangelogRelease v080 = releases.get(1);
        assertThat(v080.version()).isEqualTo("0.8.0");
        assertThat(v080.date()).isEqualTo("2026-05-08");
        assertThat(v080.sections()).extracting(s -> s.title())
                .containsExactly("Added", "Changed (breaking)");
        assertThat(v080.sections().get(1).items()).containsExactly("A breaking change");
    }

    @Test
    void foldsWrappedContinuationLinesIntoOneItem() {
        String md = """
                ## [1.0.0] - 2026-01-01

                ### Added

                - A long item that wraps
                  across two source lines
                - Another item
                """;

        List<ChangelogRelease> releases = ChangelogParser.parse(md);

        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).sections().get(0).items())
                .containsExactly("A long item that wraps across two source lines", "Another item");
    }

    @Test
    void returnsEmptyForNullBlankOrPreambleOnly() {
        assertThat(ChangelogParser.parse(null)).isEmpty();
        assertThat(ChangelogParser.parse("   ")).isEmpty();
        assertThat(ChangelogParser.parse("# Changelog\n\nJust a preamble, no releases.")).isEmpty();
    }
}
