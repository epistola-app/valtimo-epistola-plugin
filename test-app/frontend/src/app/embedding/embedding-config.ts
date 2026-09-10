// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * Runtime configuration for embedding this app in an `<iframe>` on a trusted
 * host page. See `docs/embedding.md` and ADR 0005.
 *
 * Read from `window['env']` (`assets/config.js`) rather than from an Angular
 * `environment.ts`, so a single built image can be embedded on one origin in
 * staging and another in production without a rebuild — the same mechanism
 * `epistolaEnabled` already uses.
 *
 * Deliberately fails closed at every step: unlike `epistolaEnabled` (which
 * defaults to *on*, because the plugin is the point of this app), embedding
 * defaults to *off* and stays off unless it is both explicitly enabled and
 * given at least one syntactically valid parent origin. A misconfigured
 * allowlist must never degrade into "framable by anyone".
 */

/** Effective embedding configuration. Never partially applied: see {@link readEmbeddingConfig}. */
export interface EmbeddingConfig {
  readonly enabled: boolean;
  readonly allowedParentOrigins: readonly string[];
}

const DISABLED: EmbeddingConfig = { enabled: false, allowedParentOrigins: [] };

/**
 * `envsubst` substitutes an unset variable with the empty string, and the
 * Helm/compose copies of `config.js` render values straight into single
 * quotes, so every one of these arrives as a string rather than a boolean.
 */
function isEnabledFlag(value: unknown): boolean {
  return value === true || value === 'true';
}

/**
 * Accepts exactly a scheme + host + optional port, which is all CSP
 * `frame-ancestors` and `postMessage` targetOrigin can express anyway. A
 * trailing slash is tolerated as an obvious config typo; anything carrying a
 * path, query, fragment, or credentials is rejected outright rather than
 * silently truncated to its origin — quietly widening an allowlist entry the
 * operator wrote by hand is worse than ignoring it loudly.
 */
function normalizeOrigin(candidate: string): string | null {
  const trimmed = candidate.trim().replace(/\/+$/, '');
  if (trimmed === '') return null;

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return null;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;

  return parsed.origin === trimmed ? parsed.origin : null;
}

/** Splits on commas and/or whitespace so both YAML-list-ish and CSV spellings work. */
export function parseAllowedParentOrigins(raw: unknown): string[] {
  if (typeof raw !== 'string') return [];

  const seen = new Set<string>();
  for (const candidate of raw.split(/[\s,]+/)) {
    const origin = normalizeOrigin(candidate);
    if (origin !== null) seen.add(origin);
  }
  return [...seen];
}

/**
 * Resolves the effective configuration. Returns {@link DISABLED} — not a
 * half-enabled state — whenever embedding could not be fully established, so
 * every caller can treat a truthy `enabled` as "there is at least one origin
 * to talk to" and skip re-checking the list.
 */
export function readEmbeddingConfig(runtimeWindow: unknown = globalThis): EmbeddingConfig {
  const env = (runtimeWindow as { env?: Record<string, unknown> } | undefined)?.env;
  if (!env) return DISABLED;

  if (!isEnabledFlag(env['embeddingEnabled'])) return DISABLED;

  const allowedParentOrigins = parseAllowedParentOrigins(env['embeddingAllowedParentOrigins']);
  if (allowedParentOrigins.length === 0) {
    // Enabled-but-unusable is always an operator mistake, and an invisible one:
    // the app simply refuses to be framed with no clue why. Say so.
    console.warn(
      '[embedding] embeddingEnabled is set but embeddingAllowedParentOrigins contains no valid origin ' +
        '(expected e.g. "https://epistola.app"); embedding stays disabled.',
    );
    return DISABLED;
  }

  return { enabled: true, allowedParentOrigins };
}
