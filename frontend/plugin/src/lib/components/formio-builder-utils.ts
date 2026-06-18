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

/**
 * Hides a registered custom Formio component from the builder's component palette,
 * while keeping it fully usable inside other components' `editForm`s and at runtime.
 *
 * Formio's `WebformBuilder` only adds a component to the palette when
 * `component.builderInfo && component.builderInfo.schema` is truthy. Overriding the
 * registered class's static `builderInfo` getter to `false` therefore removes it from
 * the palette. Runtime instantiation and editForm usage don't consult `builderInfo`,
 * so they are unaffected.
 *
 * Call this AFTER the component is registered (and after any `setComponent` re-registration),
 * so it targets the final class in `Formio.Components.components[type]`.
 */
export function hideFormioComponentFromBuilder(type: string): void {
  const registered = (window as any).Formio?.Components?.components?.[type];
  if (registered) {
    Object.defineProperty(registered, 'builderInfo', {
      get: () => false,
      configurable: true,
    });
  }
}
