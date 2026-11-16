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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The releases document epistola.app serves (schema: https://epistola.app/releases.schema.json).
 * Unknown fields are ignored: the website only ever adds fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EpistolaReleasesDocument(
        int schemaVersion,
        /* When the website computed the document; line statuses hold for that day. */
        Instant generatedAt,
        Map<String, ProductReleases> products
) {

    public EpistolaReleasesDocument(int schemaVersion, Map<String, ProductReleases> products) {
        this(schemaVersion, null, products);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductReleases(
            ReleaseRef stable,
            /* Legacy: the newest pre-release, even when older than stable. */
            ReleaseRef prerelease,
            /* The newest pre-release, only while it is newer than stable. */
            ReleaseRef next,
            /* Every release line, newest first, with the status the website decided. */
            List<ReleaseLine> lines,
            /* Legacy: the oldest supported line, as a floor. */
            SupportPolicy support
    ) {
        public ProductReleases(ReleaseRef stable, ReleaseRef prerelease, SupportPolicy support) {
            this(stable, prerelease, null, null, support);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseLine(
            /* major.minor, e.g. "1.0". */
            String line,
            /* The line's newest final release. */
            ReleaseRef latest,
            /* The line's last supported day (UTC, inclusive), or null while it has no successor. */
            String supportEnds,
            /* supported, ending or ended; more values may be added. */
            String status
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupportPolicy(
            String minVersion,
            String until
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseRef(
            String version,
            String releaseUrl,
            String changelogUrl,
            Instant publishedAt
    ) {}
}
