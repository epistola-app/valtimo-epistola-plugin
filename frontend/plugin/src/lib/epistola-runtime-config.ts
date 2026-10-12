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

export interface EpistolaRuntimeEnv {
  epistolaEnabled?: boolean | string;
  epistolaFeatures?: string;
}

/**
 * The optional parts of the plugin, and whether each is on when nothing says otherwise.
 *
 * A feature listed here can be switched per environment from one build artifact. The default is
 * what an installation gets for saying nothing, so adding a feature here on `false` makes it
 * opt-in — which is the useful shape while something is alpha.
 */
const FEATURE_DEFAULTS = {
  composer: true,
} as const;

export type EpistolaFeature = keyof typeof FEATURE_DEFAULTS;

interface EpistolaRuntimeWindow {
  env?: EpistolaRuntimeEnv;
}

declare global {
  interface Window extends EpistolaRuntimeWindow {}
}

function isRuntimeWindow(value: unknown): value is EpistolaRuntimeWindow {
  return typeof value === 'object' && value !== null;
}

/**
 * Reads the runtime feature flag that decides whether the Epistola plugin
 * surfaces (admin menu, /epistola route, plugin specification, Formio
 * components) should activate in the host Valtimo app.
 *
 * The flag is sourced from `window['env']['epistolaEnabled']`, populated at
 * container start by `envsubst` against `assets/config.template.js` (the
 * standard Valtimo runtime-config pattern). Defaults to enabled — only the
 * literal `false` or string `'false'` disables the plugin, matching the
 * backend's `epistola.enabled` `matchIfMissing = true` semantics.
 *
 * Exposed as a runtime helper rather than evaluated directly in `@NgModule`
 * decorator metadata because Angular's AOT compiler cannot statically resolve
 * `window` accesses (NG1010). Read from runtime code such as specification
 * property getters, route guards, or the environment initializer instead.
 */
export function isEpistolaEnabled(): boolean {
  return flagIsNotFalse('epistolaEnabled');
}

/**
 * Reads the runtime flag that decides whether the letter composer's Form.io
 * components register at all.
 *
 * The backend has had `epistola.composer.enabled=false` since the composer
 * landed, but it only ever removed the *server* side: the component stayed in
 * the Form.io palette, so an author could configure a composer against a
 * backend that serves no composer endpoints and would find out only when an
 * employee opened the form. This is the other half.
 *
 * Switched per environment with `EPISTOLA_FEATURES=-composer`; see
 * {@link isEpistolaFeatureEnabled}. Defaults to enabled, matching the
 * backend's `matchIfMissing = true`.
 */
export function isEpistolaComposerEnabled(): boolean {
  return isEpistolaFeatureEnabled('composer');
}

/**
 * Whether an optional part of the plugin is active in this environment.
 *
 * Read from `window['env']['epistolaFeatures']`: a space-separated list where each entry is
 * `+name` to turn a feature on or `-name` to turn it off, and a bare `name` means `+name`.
 * Commas are accepted too — a feature name can contain neither a space nor a comma, so there is
 * nothing to disambiguate and no operator gets caught out by the choice.
 * Everything not mentioned keeps its {@link FEATURE_DEFAULTS} value, so an installation that says
 * nothing — or that predates a feature — behaves as before.
 *
 * ```
 * EPISTOLA_FEATURES="-composer"             # keep the plugin, drop the letter composer
 * EPISTOLA_FEATURES="-composer +something"  # several in one value
 * ```
 *
 * **One key rather than one per feature** because a `window['env']` key is not cheap here: it has
 * to be added to five `config.js` copies and the Helm ConfigMap, and only the image path is
 * env-driven, so a key added in one place silently does nothing in a real deployment — which is
 * how `epistolaEnabled` came to be broken in both compose and Helm. This key is added once and
 * every later feature rides along.
 *
 * Switching the whole plugin off (`epistolaEnabled`) takes every feature with it, so this is
 * always the narrower switch.
 *
 * Unknown names are ignored with a warning rather than failing: a typo in a deployment variable
 * must not decide whether the application renders.
 */
export function isEpistolaFeatureEnabled(feature: EpistolaFeature): boolean {
  if (!isEpistolaEnabled()) return false;

  let enabled: boolean = FEATURE_DEFAULTS[feature];
  for (const entry of requestedFeatures()) {
    const on = !entry.startsWith('-');
    const name = entry.replace(/^[+-]/, '');
    if (!(name in FEATURE_DEFAULTS)) {
      // Warn once per entry, not per lookup: this runs on every registration and route guard.
      if (!warned.has(name)) {
        warned.add(name);
        console.warn(`[epistola] unknown feature '${name}' in epistolaFeatures; ignoring`);
      }
      continue;
    }
    // Last entry wins, so '-composer,+composer' enables. Predictable beats clever.
    if (name === feature) enabled = on;
  }
  return enabled;
}

const warned = new Set<string>();

function requestedFeatures(): string[] {
  const runtimeWindow = Reflect.get(globalThis, 'window');
  if (!runtimeWindow || !isRuntimeWindow(runtimeWindow)) return [];
  const raw = runtimeWindow.env ? runtimeWindow.env.epistolaFeatures : undefined;
  if (typeof raw !== 'string') return [];
  return raw
    .split(/[\s,]+/)
    .map((entry) => entry.toLowerCase())
    .filter((entry) => entry.length > 0 && entry !== '+' && entry !== '-');
}

/** Only a literal `false` or the string `'false'` disables; anything else, including absent, enables. */
function flagIsNotFalse(key: 'epistolaEnabled'): boolean {
  const runtimeWindow = Reflect.get(globalThis, 'window');
  if (!runtimeWindow) return true;
  if (!isRuntimeWindow(runtimeWindow)) return true;
  const flag = runtimeWindow.env ? runtimeWindow.env[key] : undefined;
  return flag !== false && flag !== 'false';
}
