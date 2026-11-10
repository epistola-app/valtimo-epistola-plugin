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

import java.math.BigInteger;
import java.util.Comparator;

/**
 * Orders catalog release versions by semantic-version precedence, which is how Epistola picks the
 * highest release an environment serves: {@code 1.10.0} above {@code 1.9.0}, and a pre-release
 * ({@code 2.0.0-rc.1}) below its release. Build metadata ({@code +...}) is ignored. A version that is
 * not semantic sorts below every one that is, so it is never mistaken for the highest.
 */
final class ReleaseVersions {

    static final Comparator<String> PRECEDENCE = ReleaseVersions::compare;

    private ReleaseVersions() {
    }

    static int compare(String a, String b) {
        Parsed left = Parsed.of(a);
        Parsed right = Parsed.of(b);
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : -1) : 1;
        }
        for (int i = 0; i < 3; i++) {
            int c = Long.compare(left.core[i], right.core[i]);
            if (c != 0) {
                return c;
            }
        }
        return comparePreRelease(left.preRelease, right.preRelease);
    }

    private static int comparePreRelease(String a, String b) {
        if (a == null || b == null) {
            // A release outranks its pre-releases.
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            boolean leftNumeric = left[i].matches("\\d+");
            boolean rightNumeric = right[i].matches("\\d+");
            int c;
            if (leftNumeric && rightNumeric) {
                c = new BigInteger(left[i]).compareTo(new BigInteger(right[i]));
            } else if (leftNumeric || rightNumeric) {
                c = leftNumeric ? -1 : 1;
            } else {
                c = left[i].compareTo(right[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private record Parsed(long[] core, String preRelease) {
        static Parsed of(String version) {
            if (version == null) {
                return null;
            }
            String v = version.trim();
            int plus = v.indexOf('+');
            if (plus >= 0) {
                v = v.substring(0, plus);
            }
            String preRelease = null;
            int dash = v.indexOf('-');
            if (dash >= 0) {
                preRelease = v.substring(dash + 1);
                v = v.substring(0, dash);
            }
            String[] parts = v.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            long[] core = new long[3];
            try {
                for (int i = 0; i < 3; i++) {
                    core[i] = Long.parseLong(parts[i]);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            return new Parsed(core, preRelease == null || preRelease.isEmpty() ? null : preRelease);
        }
    }
}
