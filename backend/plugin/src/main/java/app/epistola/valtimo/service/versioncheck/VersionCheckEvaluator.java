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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

public final class VersionCheckEvaluator {

    private VersionCheckEvaluator() {
    }

    public static VersionCheckStatus evaluate(
            EpistolaReleasesDocument document,
            String productKey,
            String currentVersion,
            Instant checkedAt,
            Duration deprecationWindow
    ) {
        var current = SemVersion.parse(currentVersion);
        if (current.isEmpty()) {
            return new VersionCheckStatus(
                    true, checkedAt, currentVersion, true, false, null, false,
                    null, null, null, null, null, true, false, null, null, null);
        }

        var product = document.products() != null ? document.products().get(productKey) : null;
        if (product == null) {
            return new VersionCheckStatus(
                    true, checkedAt, currentVersion, true, false, null, false,
                    null, null, null, null, null, true, false, null, null,
                    "Release metadata did not include " + productKey);
        }

        boolean preRelease = current.get().isPreRelease();
        var stable = product.stable();
        var track = preRelease && product.prerelease() != null && product.prerelease().version() != null
                ? product.prerelease()
                : stable;
        String latestVersion = track != null ? track.version() : null;
        var latest = SemVersion.parse(latestVersion);

        String minSupportedVersion = product.support() != null ? product.support().minVersion() : null;
        var minSupported = SemVersion.parse(minSupportedVersion);
        boolean supported = minSupported.isEmpty() || current.get().compareTo(minSupported.get()) >= 0;
        // `until` is the last supported day of the floor's line, the X.Y that `minVersion` names, not
        // of every supported version: a newer line ends later, or not at all, and the document does
        // not carry its date. So a version on a newer line neither reports that date nor is told its
        // support is ending. Without a parseable floor there is no line to compare against.
        boolean onNewerLine = minSupported.isPresent() && newerLine(current.get(), minSupported.get());
        String supportedUntil = product.support() != null && !onNewerLine ? product.support().until() : null;
        boolean supportEndingSoon = supported
                && withinDeprecationWindow(supportedUntil, checkedAt, deprecationWindow);
        var linkRelease = !supported && stable != null ? stable : track;

        return new VersionCheckStatus(
                true,
                checkedAt,
                currentVersion,
                true,
                preRelease,
                latestVersion,
                latest.isPresent() && latest.get().compareTo(current.get()) > 0,
                linkRelease != null ? linkRelease.releaseUrl() : null,
                linkRelease != null ? linkRelease.changelogUrl() : null,
                stable != null ? stable.version() : null,
                stable != null ? stable.releaseUrl() : null,
                stable != null ? stable.changelogUrl() : null,
                supported,
                supported && supportEndingSoon,
                minSupportedVersion,
                supportedUntil,
                latest.isEmpty() ? "Release metadata did not include a comparable version" : null
        );
    }

    /** True when {@code version}'s {@code major.minor} line comes after {@code floor}'s. */
    private static boolean newerLine(SemVersion version, SemVersion floor) {
        int major = version.core().get(0);
        int floorMajor = floor.core().get(0);
        return major > floorMajor || (major == floorMajor && version.core().get(1) > floor.core().get(1));
    }

    private static boolean withinDeprecationWindow(
            String until,
            Instant checkedAt,
            Duration window
    ) {
        if (until == null || until.isBlank()) {
            return false;
        }
        try {
            LocalDate untilDate = LocalDate.parse(until);
            LocalDate cutoff = checkedAt.atZone(ZoneOffset.UTC).toLocalDate().plusDays(window.toDays());
            return !untilDate.isAfter(cutoff);
        } catch (Exception e) {
            return false;
        }
    }
}
