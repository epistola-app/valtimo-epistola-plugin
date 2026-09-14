// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { readEmbeddingConfig } from './embedding-config';

/**
 * The seam between the authentication integration and the embedding bridge.
 *
 * Deliberately a dependency-free module rather than an Angular service: the
 * auth code runs from an `APP_INITIALIZER` and is instantiated by
 * `environments/auth/*`, long before — and independently of — the bridge's own
 * module. A plain listener registry lets the two talk without either importing
 * the other's Angular wiring, and keeps both unit-testable in isolation.
 *
 * See `docs/embedding.md` — "Not solved: interactive re-authentication" is what
 * this exists to solve.
 */

/**
 * Why the app cannot get a token without a human: the OIDC error a `prompt=none`
 * attempt came back with. Usually `login_required`; `consent_required`,
 * `interaction_required` and `account_selection_required` are the other
 * standard values, and a provider may define its own — so this is deliberately
 * the raw string rather than a closed union the app would have to keep in sync.
 */
export type InteractiveAuthReason = string;

type Listener<T> = (value: T) => void;

const authRequiredListeners = new Set<Listener<InteractiveAuthReason>>();
const retryListeners = new Set<Listener<void>>();

function subscribe<T>(set: Set<Listener<T>>, listener: Listener<T>): () => void {
  set.add(listener);
  return () => set.delete(listener);
}

/** The bridge listens; the auth integration raises. Returns an unsubscribe function. */
export function onInteractiveAuthRequired(listener: Listener<InteractiveAuthReason>): () => void {
  return subscribe(authRequiredListeners, listener);
}

/**
 * Raised when the app has established that it needs a human to sign in, and
 * has deliberately *not* navigated its own frame to say so — navigating is
 * precisely what leaves the user staring at a blank rectangle, because an
 * identity provider can redirect through a frame but cannot render in one.
 */
export function notifyInteractiveAuthRequired(reason: InteractiveAuthReason): void {
  for (const listener of [...authRequiredListeners]) listener(reason);
}

/** The auth integration listens; the bridge raises when the host says it is done. */
export function onAuthRetryRequested(listener: Listener<void>): () => void {
  return subscribe(retryListeners, listener);
}

/** Raised when the host reports that it has completed a top-level sign-in. */
export function notifyAuthRetryRequested(): void {
  for (const listener of [...retryListeners]) listener();
}

/**
 * Whether this document is a live embedded session — embedding configured *and*
 * actually framed. The auth integration branches on this so that a normal,
 * un-framed deployment keeps its existing full-redirect behaviour untouched.
 */
export function isEmbeddedSession(runtimeWindow: unknown = globalThis): boolean {
  const candidate = runtimeWindow as (Window & { parent?: Window }) | undefined;
  if (!candidate || !candidate.parent || candidate.parent === candidate) return false;
  return readEmbeddingConfig(candidate).enabled;
}

/** Test seam: drops every registration so one spec cannot leak into the next. */
export function resetEmbeddedAuthListeners(): void {
  authRequiredListeners.clear();
  retryListeners.clear();
}
