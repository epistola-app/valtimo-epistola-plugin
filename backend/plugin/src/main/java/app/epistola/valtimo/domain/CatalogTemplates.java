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
 * Every template in the releases of a catalog a link can reach, with the releases each is in — what
 * a configurator offers when the link's release is an expression, known only when it generates
 * (ADR 0009, addendum). With a literal range or none, one release renders and a configurator lists
 * that release's templates instead.
 *
 * @param catalogId the catalog, qualified
 * @param templates the templates, by name
 * @param truncated whether only the newest {@code EpistolaServiceImpl.MAX_TEMPLATE_RELEASES} releases
 *                  were read, so a template only in an older one is not listed
 */
public record CatalogTemplates(String catalogId, List<Template> templates, boolean truncated) {

    /**
     * One template and where it exists.
     *
     * @param id          the template's key
     * @param name        its name in the highest release that contains it
     * @param description its description there, or null
     * @param releases    the releases that contain it, highest first: a draft first, as
     *                    {@code "draft"}, then released versions
     * @param read        the selection a lookup sends to read it from the highest of those, for its
     *                    fields and variants
     */
    public record Template(
            String id, String name, String description, List<String> releases, DesignScope.Read read) {
    }
}
