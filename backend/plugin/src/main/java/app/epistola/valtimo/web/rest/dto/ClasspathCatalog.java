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
package app.epistola.valtimo.web.rest.dto;

/**
 * A catalog discovered on the application classpath, with whether it currently
 * exists in the connected Epistola installation. Existence is resolved by
 * querying Epistola at request time (Epistola does not expose a catalog version,
 * so this is presence, not a version comparison).
 *
 * <p>{@code status}:
 * <ul>
 *   <li>{@code IN_EPISTOLA} — a catalog with this slug exists in Epistola.</li>
 *   <li>{@code NOT_IN_EPISTOLA} — Epistola was reached and has no such catalog
 *       (e.g. the startup auto-deploy failed or was skipped); use Redeploy.</li>
 *   <li>{@code UNKNOWN} — Epistola could not be reached, so existence could not
 *       be determined (not asserted as missing — reachability is the health tab's
 *       job).</li>
 * </ul>
 */
public record ClasspathCatalog(
        String slug,
        String version,
        String status
) {
    public static final String IN_EPISTOLA = "IN_EPISTOLA";
    public static final String NOT_IN_EPISTOLA = "NOT_IN_EPISTOLA";
    public static final String UNKNOWN = "UNKNOWN";
}
