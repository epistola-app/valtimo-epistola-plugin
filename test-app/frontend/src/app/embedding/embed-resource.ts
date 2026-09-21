// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * The closed vocabulary the host page and this app use to name a location,
 * and the only bridge between it and a real Valtimo URL. See `docs/embedding.md`.
 *
 * The host may only ever hand over a typed identity, never a URL or a router
 * path. That is the load-bearing security property of the whole bridge: with a
 * raw path the host could aim the iframe at any route, which is open-redirect
 * shaped even though it stays same-origin (it would, for example, let a host
 * page drive an authenticated user's session to an admin screen it should have
 * no say over). {@link resolveRouteCommands} is a fixed lookup over a finite
 * set of shapes with every identifier format-checked, so there is no input that
 * produces a path outside those shapes.
 *
 * Note this is *not* the authorization boundary — it is the addressing
 * boundary. Navigation is still performed through Valtimo's own `Router`, so
 * `AuthGuardService` and every route's role guard run exactly as they do for an
 * in-app link click. There is deliberately no privileged "host navigation" path
 * that could drift away from those checks.
 */

/** A location in the app, as identity rather than as URL. */
export type EmbedResource =
  | { readonly view: 'home' }
  | { readonly view: 'cases' }
  | { readonly view: 'case-type'; readonly caseDefinitionKey: string }
  | {
      readonly view: 'case';
      readonly caseDefinitionKey: string;
      readonly documentId: string;
      readonly tab?: string;
    }
  | {
      readonly view: 'task';
      readonly caseDefinitionKey: string;
      readonly documentId: string;
      readonly tab: string;
      readonly taskId: string;
    }
  | { readonly view: 'tasks' }
  | { readonly view: 'processes' }
  | { readonly view: 'plugins' }
  | { readonly view: 'process-links' }
  | { readonly view: 'epistola-admin' };

/**
 * Identifiers that go into a path segment. Excludes `.` entirely, so no input
 * can form `..` and climb out of the shape it was placed in, and excludes `/`,
 * `?`, `#` and everything else that could add structure to the URL. Wide enough
 * for real Valtimo keys: both authored ones (`form-flow-demo`) and the training
 * facility's generated per-trainee keys (`t` + 15 hex chars, see `TraineeKeys`).
 */
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;

/** Valtimo case documents are `JsonSchemaDocumentId`, i.e. always a UUID. */
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

const isSafeId = (value: unknown): value is string =>
  typeof value === 'string' && SAFE_ID.test(value);

const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value);

/** Static views, by name. Kept separate from the parameterised ones so the flat cases stay a table. */
const STATIC_VIEW_COMMANDS: Readonly<Record<string, readonly string[]>> = {
  home: ['/'],
  cases: ['/cases'],
  tasks: ['/tasks'],
  processes: ['/processes'],
  plugins: ['/plugins'],
  'process-links': ['/process-links'],
  'epistola-admin': ['/epistola'],
};

/**
 * Turns a host-supplied identity into Angular `Router` commands, or `null` if
 * it is not one of the known shapes or carries a malformed identifier.
 *
 * Takes `unknown` on purpose: the input arrives from `postMessage` and is
 * attacker-shaped by construction, so it is validated here rather than trusted
 * to match {@link EmbedResource} because a caller said so.
 */
export function resolveRouteCommands(resource: unknown): string[] | null {
  if (typeof resource !== 'object' || resource === null) return null;
  const candidate = resource as Record<string, unknown>;
  const view = candidate['view'];
  if (typeof view !== 'string') return null;

  const staticCommands = Object.prototype.hasOwnProperty.call(STATIC_VIEW_COMMANDS, view)
    ? STATIC_VIEW_COMMANDS[view]
    : undefined;
  if (staticCommands) return [...staticCommands];

  const { caseDefinitionKey, documentId, tab, taskId } = candidate;

  if (view === 'case-type') {
    return isSafeId(caseDefinitionKey) ? ['/cases', caseDefinitionKey] : null;
  }

  if (view === 'case') {
    if (!isSafeId(caseDefinitionKey) || !isUuid(documentId)) return null;
    const base = ['/cases', caseDefinitionKey, 'document', documentId];
    // `tab` is optional: Valtimo registers the case route both with and without
    // it, and which tabs exist is per-case-definition configuration the host
    // has no way to know. Omitted means "whichever tab the app opens by default".
    if (tab === undefined || tab === null) return base;
    return isSafeId(tab) ? [...base, tab] : null;
  }

  if (view === 'task') {
    if (!isSafeId(caseDefinitionKey) || !isUuid(documentId)) return null;
    // Unlike `case`, the tab is required here — the task route is nested under
    // a tab segment, so there is no shorter form to fall back to.
    if (!isSafeId(tab) || !isSafeId(taskId)) return null;
    return ['/cases', caseDefinitionKey, 'document', documentId, tab, 'tasks', taskId];
  }

  return null;
}

/**
 * The reverse direction: the app's own current URL back into an identity, so a
 * `navigated` message can tell the host *what* the user is looking at and not
 * just where. Derived client-side from the URL alone — the URL shape already is
 * the identity scheme, so there is nothing for the server to contribute.
 *
 * Returns `null` for any route that is not one of the known shapes (a list, an
 * admin screen, a 404). That is a normal outcome, not an error: `navigated`
 * still carries the raw `path`.
 */
export function parseResourceFromUrl(url: string): EmbedResource | null {
  const path = url.split(/[?#]/)[0];
  const segments = path.split('/').filter((segment) => segment !== '');

  if (segments.length === 0) return { view: 'home' };

  for (const [view, commands] of Object.entries(STATIC_VIEW_COMMANDS)) {
    if (commands.length === 1 && commands[0] === `/${segments[0]}` && segments.length === 1) {
      return { view } as EmbedResource;
    }
  }

  if (segments[0] !== 'cases') return null;

  const [, caseDefinitionKey, documentLiteral, documentId, tab, tasksLiteral, taskId] = segments;

  if (segments.length === 2) {
    return isSafeId(caseDefinitionKey) ? { view: 'case-type', caseDefinitionKey } : null;
  }

  if (documentLiteral !== 'document' || !isSafeId(caseDefinitionKey) || !isUuid(documentId)) {
    return null;
  }

  if (segments.length === 4) return { view: 'case', caseDefinitionKey, documentId };

  if (segments.length === 5) {
    return isSafeId(tab) ? { view: 'case', caseDefinitionKey, documentId, tab } : null;
  }

  if (segments.length === 7 && tasksLiteral === 'tasks') {
    return isSafeId(tab) && isSafeId(taskId)
      ? { view: 'task', caseDefinitionKey, documentId, tab, taskId }
      : null;
  }

  return null;
}
