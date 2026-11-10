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
package app.epistola.valtimo.domain;

import java.util.List;

/**
 * The release a configurator designs a link against, resolved once so that every lookup it makes —
 * templates, a template's fields, its variants, the catalog's attributes — reads the same one
 * (ADR 0009).
 * <p>
 * Resolution falls back rather than failing: a catalog not deployed to the link's environment, or a
 * range nothing there satisfies, is read catalog-wide, and a range no release satisfies is dropped.
 * Each fallback is reported in {@code problems}, as a warning: generation itself stays strict.
 *
 * @param catalogId   the catalog, qualified ({@code registry/namespace/key}) also when it was named
 *                    by a bare key saved against contract 1.x
 * @param versions    released versions to suggest for the link's range, highest first: those
 *                    deployed to the environment when it was read there, else every one
 * @param version     the release designed against, or null when that is a draft
 * @param draft       whether a draft is designed against: the environment's deployed draft, or the
 *                    catalog's newest when it was never released
 * @param environment the environment it was read in, or null when it was read catalog-wide
 * @param problems    why it was not read where and how the link renders, in the order they arose
 * @param read        the selection every further lookup sends, always concrete: an environment's
 *                    draft, or an exact version
 */
public record DesignScope(
        String catalogId,
        List<String> versions,
        String version,
        boolean draft,
        String environment,
        List<Problem> problems,
        Read read
) {

    /** Why a design scope is not where, or not how, the link renders. */
    public enum Problem {
        /** The link's environment does not exist (an environment expression's literal, mistyped). */
        UNKNOWN_ENVIRONMENT,
        /** No release of the catalog is deployed to the link's environment. */
        NOT_DEPLOYED,
        /** The catalog is deployed to the link's environment, but no release there satisfies its range. */
        RANGE_NOT_DEPLOYED,
        /** No release of the catalog satisfies the link's range at all. */
        RANGE_UNSATISFIED
    }

    /**
     * What a lookup sends to read this scope's release.
     *
     * @param environmentId the environment, or null to read catalog-wide
     * @param versionRange  an exact version, or null for the environment's draft (else its highest
     *                      release) or, catalog-wide, the catalog's newest draft
     */
    public record Read(String environmentId, String versionRange) {
    }
}
