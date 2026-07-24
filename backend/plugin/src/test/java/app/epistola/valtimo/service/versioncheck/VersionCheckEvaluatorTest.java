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

import app.epistola.valtimo.web.rest.dto.VersionCheckStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VersionCheckEvaluatorTest {

    private static final String PRODUCT_KEY = "valtimo-epistola-plugin";
    private final Instant checkedAt = Instant.parse("2026-07-08T10:00:00Z");

    @Test
    void stableBuildsTrackLatestStableRelease() {
        VersionCheckStatus status = VersionCheckEvaluator.evaluate(
                document(
                        release("1.0.1", null),
                        release("1.1.0-RC1", null),
                        null),
                PRODUCT_KEY,
                "1.0.0",
                checkedAt,
                Duration.ofDays(90)
        );

        assertThat(status.preRelease()).isFalse();
        assertThat(status.latestVersion()).isEqualTo("1.0.1");
        assertThat(status.latestStableVersion()).isEqualTo("1.0.1");
        assertThat(status.updateAvailable()).isTrue();
    }

    @Test
    void preReleaseBuildsTrackLatestPreReleaseAndReferenceStable() {
        VersionCheckStatus status = VersionCheckEvaluator.evaluate(
                document(
                        release("1.0.0", "https://example.test/stable"),
                        release("1.1.0-RC2", "https://example.test/rc"),
                        null),
                PRODUCT_KEY,
                "1.1.0-RC1-SNAPSHOT",
                checkedAt,
                Duration.ofDays(90)
        );

        assertThat(status.preRelease()).isTrue();
        assertThat(status.latestVersion()).isEqualTo("1.1.0-RC2");
        assertThat(status.releaseUrl()).isEqualTo("https://example.test/rc");
        assertThat(status.updateAvailable()).isTrue();
        assertThat(status.latestStableVersion()).isEqualTo("1.0.0");
        assertThat(status.stableReleaseUrl()).isEqualTo("https://example.test/stable");
    }

    @Test
    void versionsBelowMinimumSupportedVersionAreUnsupported() {
        VersionCheckStatus status = VersionCheckEvaluator.evaluate(
                document(
                        release("1.2.0", "https://example.test/stable"),
                        null,
                        new EpistolaReleasesDocument.SupportPolicy("1.1.0", "2027-01-31")),
                PRODUCT_KEY,
                "1.0.0",
                checkedAt,
                Duration.ofDays(90)
        );

        assertThat(status.supported()).isFalse();
        assertThat(status.minSupportedVersion()).isEqualTo("1.1.0");
        assertThat(status.supportedUntil()).isEqualTo("2027-01-31");
        assertThat(status.releaseUrl()).isEqualTo("https://example.test/stable");
    }

    @Test
    void supportEndingWithinWindowFlagsWarning() {
        VersionCheckStatus status = VersionCheckEvaluator.evaluate(
                document(
                        release("1.2.0", null),
                        null,
                        new EpistolaReleasesDocument.SupportPolicy("1.1.0", "2026-08-01")),
                PRODUCT_KEY,
                "1.1.0",
                checkedAt,
                Duration.ofDays(90)
        );

        assertThat(status.supported()).isTrue();
        assertThat(status.supportEndingSoon()).isTrue();
    }

    @Test
    void missingProductMetadataDoesNotReportUpdate() {
        VersionCheckStatus status = VersionCheckEvaluator.evaluate(
                new EpistolaReleasesDocument(1, Map.of()),
                PRODUCT_KEY,
                "1.0.0",
                checkedAt,
                Duration.ofDays(90)
        );

        assertThat(status.updateAvailable()).isFalse();
        assertThat(status.lastError()).contains(PRODUCT_KEY);
    }

    private static EpistolaReleasesDocument document(
            EpistolaReleasesDocument.ReleaseRef stable,
            EpistolaReleasesDocument.ReleaseRef prerelease,
            EpistolaReleasesDocument.SupportPolicy support
    ) {
        return new EpistolaReleasesDocument(1, Map.of(
                PRODUCT_KEY,
                new EpistolaReleasesDocument.ProductReleases(stable, prerelease, support)
        ));
    }

    private static EpistolaReleasesDocument.ReleaseRef release(String version, String url) {
        return new EpistolaReleasesDocument.ReleaseRef(version, url, null, null);
    }
}
