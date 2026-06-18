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
import app.epistola.valtimo.web.rest.dto.ChangelogSection;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a "Keep a Changelog" formatted CHANGELOG.md into structured releases
 * so the admin UI can present it without a markdown renderer.
 *
 * <p>Recognises {@code ## [version] - date} (date optional) as a release,
 * {@code ### Title} as a section, and {@code - } / {@code * } bullets as items;
 * wrapped continuation lines are folded into the preceding item. Anything before
 * the first release heading (the {@code # Changelog} title and preamble) is
 * ignored. Unparseable input yields an empty list rather than throwing.
 */
final class ChangelogParser {

    private static final Pattern RELEASE =
            Pattern.compile("^##\\s+\\[([^\\]]+)\\]\\s*(?:-\\s*(.+?))?\\s*$");

    private ChangelogParser() {}

    static List<ChangelogRelease> parse(String markdown) {
        List<ChangelogRelease> releases = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return releases;
        }

        String version = null;
        String date = null;
        List<ChangelogSection> sections = new ArrayList<>();
        String sectionTitle = null;
        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();

        // Local "finalizers" expressed inline (no lambdas — they need to mutate
        // several locals); see the helper calls below.
        for (String raw : markdown.split("\r?\n", -1)) {
            String line = raw.strip();
            Matcher releaseMatch = RELEASE.matcher(line);

            if (releaseMatch.matches()) {
                // close item -> section -> release
                if (item.length() > 0) { items.add(item.toString()); item.setLength(0); }
                if (sectionTitle != null) { sections.add(new ChangelogSection(sectionTitle, List.copyOf(items))); }
                if (version != null) { releases.add(new ChangelogRelease(version, date, List.copyOf(sections))); }
                version = releaseMatch.group(1).strip();
                date = releaseMatch.group(2) != null ? releaseMatch.group(2).strip() : null;
                sections = new ArrayList<>();
                sectionTitle = null;
                items = new ArrayList<>();
            } else if (version != null && line.startsWith("### ")) {
                if (item.length() > 0) { items.add(item.toString()); item.setLength(0); }
                if (sectionTitle != null) { sections.add(new ChangelogSection(sectionTitle, List.copyOf(items))); }
                sectionTitle = line.substring(4).strip();
                items = new ArrayList<>();
            } else if (sectionTitle != null && (line.startsWith("- ") || line.startsWith("* "))) {
                if (item.length() > 0) { items.add(item.toString()); item.setLength(0); }
                item.append(line.substring(2).strip());
            } else if (item.length() > 0 && !line.isEmpty()) {
                // wrapped continuation of the current bullet
                item.append(' ').append(line);
            } else if (line.isEmpty() && item.length() > 0) {
                items.add(item.toString());
                item.setLength(0);
            }
            // else: preamble / blank / stray line before any release — ignore
        }

        // flush trailing item/section/release
        if (item.length() > 0) { items.add(item.toString()); }
        if (sectionTitle != null) { sections.add(new ChangelogSection(sectionTitle, List.copyOf(items))); }
        if (version != null) { releases.add(new ChangelogRelease(version, date, List.copyOf(sections))); }

        return releases;
    }
}
