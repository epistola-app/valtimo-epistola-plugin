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
