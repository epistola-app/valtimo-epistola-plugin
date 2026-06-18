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
}

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
  const runtimeWindow = Reflect.get(globalThis, 'window');
  if (!runtimeWindow) return true;
  if (!isRuntimeWindow(runtimeWindow)) return true;
  const flag = runtimeWindow.env ? runtimeWindow.env.epistolaEnabled : undefined;
  return flag !== false && flag !== 'false';
}
