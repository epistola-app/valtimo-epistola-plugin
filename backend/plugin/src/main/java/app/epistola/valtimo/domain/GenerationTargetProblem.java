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

/**
 * Why Epistola refused a generation or preview it could not resolve — what was asked for, and the
 * first part of it that does not exist (ADR 0009, addendum). Everything here is configuration: the
 * catalog, template, variant, release, environment and range a link or composer names. None of it
 * is case data, so it may be logged; Epistola's own text about the refusal may not, and is not here.
 *
 * @param reason        the first part of the target that does not exist
 * @param catalogId     the catalog asked for, as the link names it
 * @param templateId    the template asked for
 * @param variantId     the variant asked for, or null when it was chosen by attributes or default
 * @param environmentId the environment it was to render in, or null for none
 * @param versionRange  the range it was to render, or null for none
 * @param release       the release looked in — its version, {@code "draft"} for a deployed or
 *                      newest draft — or null when the refusal came before a release was found
 */
public record GenerationTargetProblem(
        Reason reason,
        String catalogId,
        String templateId,
        String variantId,
        String environmentId,
        String versionRange,
        String release
) {

    /** The first part of a generation target that does not exist. */
    public enum Reason {
        ENVIRONMENT_NOT_FOUND,
        CATALOG_NOT_FOUND,
        /** No release of the catalog is deployed to the environment. */
        NOT_DEPLOYED,
        /** The catalog has no release at all to render from. */
        NOT_RELEASED,
        /** No release (deployed to the environment, when there is one) satisfies the range. */
        RANGE_UNSATISFIED,
        TEMPLATE_NOT_IN_RELEASE,
        VARIANT_NOT_IN_RELEASE,
        /** No variant of the template matches the attributes asked for. */
        NO_MATCHING_VARIANT,
        /** Epistola refused it as not found, and the plugin could not tell what. */
        UNKNOWN
    }

    /** What went wrong, for a person: names what was asked for and what to do about it. */
    public String describe() {
        String catalog = "catalog '" + catalogId + "'";
        String in = environmentId == null ? "" : " in environment '" + environmentId + "'";
        String selecting = versionRange == null ? "" : " (selected by \"" + versionRange + "\"" + in + ")";
        String inRelease = release == null ? ""
                : "draft".equals(release) ? " in the draft" + in : " in release " + release + selecting;
        return switch (reason) {
            case ENVIRONMENT_NOT_FOUND -> "Environment '" + environmentId + "' does not exist in Epistola. "
                    + "Correct the environment on the link or composer.";
            case CATALOG_NOT_FOUND -> "Catalog '" + catalogId + "' does not exist in Epistola. "
                    + "Choose another catalog on the link or composer.";
            case NOT_DEPLOYED -> "Catalog '" + catalogId + "' is not deployed to environment '" + environmentId
                    + "'. Deploy a release of it there in Epistola.";
            case NOT_RELEASED -> "Catalog '" + catalogId + "' has no release to render from. "
                    + "Release it in Epistola.";
            case RANGE_UNSATISFIED -> "No release of " + catalog
                    + (environmentId == null ? "" : " deployed to environment '" + environmentId + "'")
                    + " satisfies \"" + versionRange + "\". Deploy or release one that does, or change the release.";
            case TEMPLATE_NOT_IN_RELEASE -> "Template '" + templateId + "' is not in " + catalog + inRelease
                    + ". Choose a release that contains it, or another template.";
            case VARIANT_NOT_IN_RELEASE -> "Variant '" + variantId + "' of template '" + templateId + "' is not in "
                    + catalog + inRelease + ". Choose another variant or release.";
            case NO_MATCHING_VARIANT -> "No variant of template '" + templateId + "' in " + catalog
                    + " matches the attributes asked for. Add one in Epistola, or change the attributes.";
            case UNKNOWN -> "Epistola could not find what template '" + templateId + "' in " + catalog
                    + " needs" + in + ".";
        };
    }
}
