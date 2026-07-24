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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minimal SemVer parser/comparator for release metadata.
 *
 * <p>Build metadata is ignored for precedence and a trailing {@code -SNAPSHOT}
 * development suffix is stripped before parsing.
 */
public record SemVersion(
        List<Integer> core,
        List<String> preRelease
) implements Comparable<SemVersion> {

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    public static Optional<SemVersion> parse(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        String withoutBuild = normalize(version.trim()).split("\\+", 2)[0];
        String[] coreAndPre = withoutBuild.split("-", 2);
        String[] coreParts = coreAndPre[0].split("\\.");
        if (coreParts.length != 3) {
            return Optional.empty();
        }
        List<Integer> core = new ArrayList<>(3);
        for (String part : coreParts) {
            try {
                core.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        List<String> pre = coreAndPre.length == 2 && !coreAndPre[1].isBlank()
                ? List.of(coreAndPre[1].split("\\."))
                : List.of();
        return Optional.of(new SemVersion(List.copyOf(core), pre));
    }

    public static String normalize(String version) {
        return version.endsWith("-SNAPSHOT")
                ? version.substring(0, version.length() - "-SNAPSHOT".length())
                : version;
    }

    @Override
    public int compareTo(SemVersion other) {
        for (int i = 0; i < 3; i++) {
            int cmp = core.get(i).compareTo(other.core.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) {
            return 0;
        }
        if (preRelease.isEmpty()) {
            return 1;
        }
        if (other.preRelease.isEmpty()) {
            return -1;
        }
        int shared = Math.min(preRelease.size(), other.preRelease.size());
        for (int i = 0; i < shared; i++) {
            int cmp = comparePreReleaseId(preRelease.get(i), other.preRelease.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }

    private static int comparePreReleaseId(String a, String b) {
        Integer an = parseIntOrNull(a);
        Integer bn = parseIntOrNull(b);
        if (an != null && bn != null) {
            return an.compareTo(bn);
        }
        if (an != null) {
            return -1;
        }
        if (bn != null) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
