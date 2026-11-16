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

import app.epistola.valtimo.service.versioncheck.EpistolaReleasesDocument.ProductReleases;
import app.epistola.valtimo.service.versioncheck.EpistolaReleasesDocument.ReleaseLine;
import app.epistola.valtimo.service.versioncheck.EpistolaReleasesDocument.ReleaseRef;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the releases document says about the running plugin version, read from {@code lines}.
 *
 * <p>The website decides each line's status, so a policy change (a grace period, an extension)
 * needs no plugin release. This class only looks the version up, by the rules every client
 * follows (https://epistola.app/releases.schema.json), and passes the published test cases
 * (https://epistola.app/releases.conformance.json, copied into this module's test resources):
 *
 * <ol>
 *   <li>Find the entry in {@code lines} for the version's major.minor.</li>
 *   <li>No entry: a version older than every listed line is {@code ENDED}; any other (a
 *       development or pre-release build of a line not released yet) is {@code UNKNOWN}.</li>
 *   <li>Otherwise the entry's status; a value this client does not know is {@code UNKNOWN}.</li>
 *   <li>{@code patch}: the entry's latest release when newer than the running version.</li>
 *   <li>{@code newerRelease}: the newest stable release when it is on a newer line.</li>
 *   <li>{@code nextPrerelease}: for a pre-release build, the newest pre-release when newer.</li>
 * </ol>
 *
 * <p>{@code UNKNOWN} means: say nothing about support.
 */
public final class LineLookup {

    public enum Status { SUPPORTED, ENDING, ENDED, UNKNOWN }

    public record Result(
            Status status,
            /* The matched line, e.g. "1.0", or null without an entry. */
            String line,
            /* The matched line's last supported day, or null. */
            String supportEnds,
            ReleaseRef patch,
            ReleaseRef newerRelease,
            ReleaseRef nextPrerelease
    ) {
        static final Result UNKNOWN = new Result(Status.UNKNOWN, null, null, null, null, null);
    }

    private LineLookup() {
    }

    /** The answer for {@code version}; {@link Result#UNKNOWN} without lines or a SemVer version. */
    public static Result evaluate(ProductReleases product, String version) {
        Optional<SemVersion> parsed = SemVersion.parseStrict(version);
        if (product == null || product.lines() == null || parsed.isEmpty()) {
            return Result.UNKNOWN;
        }
        SemVersion running = parsed.get();
        int[] own = lineOf(running);
        List<ListedLine> listed = product.lines().stream()
                .filter(Objects::nonNull)
                .map(entry -> new ListedLine(entry, parseLine(entry.line())))
                .filter(l -> l.line() != null)
                .toList();
        ReleaseLine match = listed.stream()
                .filter(l -> compareLines(l.line(), own) == 0)
                .map(ListedLine::entry)
                .findFirst()
                .orElse(null);

        ReleaseRef stable = product.stable();
        ReleaseRef newerRelease = SemVersion.parseStrict(stable != null ? stable.version() : null)
                .filter(s -> compareLines(lineOf(s), own) > 0)
                .map(s -> stable)
                .orElse(null);
        ReleaseRef nextPrerelease = running.isPreRelease() ? newer(product.next(), running) : null;

        if (match == null) {
            boolean olderThanAll = !listed.isEmpty()
                    && listed.stream().allMatch(l -> compareLines(own, l.line()) < 0);
            return new Result(olderThanAll ? Status.ENDED : Status.UNKNOWN,
                    null, null, null, newerRelease, nextPrerelease);
        }
        return new Result(status(match.status()), match.line(), match.supportEnds(),
                newer(match.latest(), running), newerRelease, nextPrerelease);
    }

    private record ListedLine(ReleaseLine entry, int[] line) {}

    private static Status status(String value) {
        if (value == null) {
            return Status.UNKNOWN;
        }
        return switch (value) {
            case "supported" -> Status.SUPPORTED;
            case "ending" -> Status.ENDING;
            case "ended" -> Status.ENDED;
            default -> Status.UNKNOWN;
        };
    }

    /** Orders two versions by their {@code major.minor} line alone, ignoring patch and pre-release. */
    static int compareLines(SemVersion a, SemVersion b) {
        return compareLines(lineOf(a), lineOf(b));
    }

    private static int[] lineOf(SemVersion version) {
        return new int[] {version.core().get(0), version.core().get(1)};
    }

    private static int compareLines(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }

    private static final Pattern LINE = Pattern.compile("\\d+\\.\\d+");

    private static int[] parseLine(String line) {
        if (line == null || !LINE.matcher(line).matches()) {
            return null;
        }
        String[] parts = line.split("\\.");
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ReleaseRef newer(ReleaseRef ref, SemVersion than) {
        if (ref == null) {
            return null;
        }
        return SemVersion.parseStrict(ref.version())
                .filter(version -> version.compareTo(than) > 0)
                .map(version -> ref)
                .orElse(null);
    }
}
