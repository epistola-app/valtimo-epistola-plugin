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
 * The released releases of a catalog a process link may pin, and the one it renders.
 *
 * @param versions the catalog's released versions, highest first: in an environment, those deployed
 *                 to it. Drafts are left out, because a version range never matches a draft
 * @param selected the version a link with the given range renders, or {@code null} when none
 *                 satisfies it. Without a range, the highest; in an environment that serves the
 *                 catalog's draft, {@code null} with {@code draft} true
 * @param draft    whether the environment renders the catalog's deployed draft, which it does when
 *                 the link names no range
 */
public record CatalogReleases(List<String> versions, String selected, boolean draft) {
}
