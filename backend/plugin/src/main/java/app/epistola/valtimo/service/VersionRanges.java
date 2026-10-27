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

import org.semver4j.Semver;
import org.semver4j.SemverException;
import org.semver4j.processor.Processor;
import org.semver4j.processor.Processors;
import org.semver4j.range.RangeList;
import org.semver4j.range.RangeListFactory;

import java.util.Optional;

/**
 * Catalog version ranges as contract 2.0 defines them ("Versions and version ranges"): npm's SemVer
 * range grammar — an exact version ({@code 1.4.0}), {@code ^1.4.0}, {@code ~1.4.0}, comparators
 * ({@code >=1.0.0 <2.0.0}), a hyphen range, or a {@code ||} union of these. As in npm, a range
 * matches a patch release ({@code 1.3.1-gemeente-x.1}) only when one of its comparators names a
 * pre-release of the same core version, so {@code ^1.3.0} never selects one.
 * <p>
 * The server resolves a range when it renders. The plugin matches the same range to read the
 * release a configurator shows, so the two must agree; semver4j follows npm here.
 */
public final class VersionRanges {

    /** The longest range the contract accepts. */
    public static final int MAX_LENGTH = 256;

    /**
     * npm's range forms only. semver4j's default also reads Ivy's ({@code [1.0,2.0)}, {@code latest}),
     * which the contract does not accept and the server would refuse.
     */
    private static final Processor[] NPM = {
            Processors.allVersions(), Processors.hyphen(), Processors.caret(), Processors.tilde(), Processors.xRange()
    };

    private VersionRanges() {
    }

    /**
     * Why {@code range} is not a version range the contract accepts, or empty when it is. A blank
     * range is no range at all and is not checked here.
     * <p>
     * semver4j reads what it cannot parse as a range that matches nothing, so each {@code ||}
     * alternative is checked to have parsed into something; npm refuses such a range outright.
     */
    public static Optional<String> problem(String range) {
        if (range.length() > MAX_LENGTH) {
            return Optional.of("Version range is longer than " + MAX_LENGTH + " characters");
        }
        for (String alternative : range.split("\\|\\|", -1)) {
            if (alternative.isBlank() || create(alternative).get().isEmpty()) {
                return Optional.of("Invalid version range '" + range + "'");
            }
        }
        return Optional.empty();
    }

    /**
     * The parsed range, refused with a 400 when it is not one the contract accepts.
     */
    static RangeList parse(String range) {
        problem(range).ifPresent(message -> {
            throw new EpistolaApiException(message, null, 400, null, java.util.Map.of());
        });
        return create(range);
    }

    private static RangeList create(String range) {
        return RangeListFactory.create(range, false, NPM);
    }

    /** Whether {@code version} satisfies {@code range}; a version that is not SemVer never does. */
    static boolean satisfies(String version, RangeList range) {
        if (version == null) {
            return false;
        }
        try {
            return range.isSatisfiedBy(new Semver(version));
        } catch (SemverException e) {
            return false;
        }
    }
}
