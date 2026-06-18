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

import { PluginUsageEntry } from '../../models';

/**
 * The plugin action definition key the backend serializes for generate-document
 * process links. It carries the `epistola-` prefix — see `EPISTOLA_ACTION_KEYS`
 * in `EpistolaAdminService` on the backend. The selector must match this exact
 * value; the un-prefixed `generate-document` never appears in the API response.
 */
export const GENERATE_DOCUMENT_ACTION_KEY = 'epistola-generate-document';

/** Keeps only the generate-document process links from a usage overview. */
export function filterGenerateDocumentEntries(entries: PluginUsageEntry[]): PluginUsageEntry[] {
  return entries.filter((e) => e.actionKey === GENERATE_DOCUMENT_ACTION_KEY);
}
