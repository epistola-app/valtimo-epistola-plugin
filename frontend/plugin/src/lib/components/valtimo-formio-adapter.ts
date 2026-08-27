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

import { Injector, Type } from '@angular/core';
import {
  createCustomFormioComponent,
  FormioCustomComponentInfo,
  registerCustomFormioComponent,
} from '@valtimo/components';
import { Components } from 'formiojs';
import { ensureTaskIdCarrier } from '../services/prefilled-task-id';

export type ValtimoFormioComponentConstructor = ReturnType<typeof createCustomFormioComponent>;
export type ValtimoFormioComponent = InstanceType<ValtimoFormioComponentConstructor>;
export type ValtimoFormioComponentEnhancer = (
  baseComponent: ValtimoFormioComponentConstructor,
) => ValtimoFormioComponentConstructor;

/**
 * Enhancer that keeps the hidden task-id carrier in the component's <b>persisted</b> schema.
 *
 * <p>Formio's {@code Component.get schema()} serializes only what <i>differs</i> from the
 * registered default schema ({@code getModifiedSchema}); an array that deep-equals the default
 * is classified "unmodified" and dropped. Because each task-bound component declares the carrier
 * in its default {@code schema}, the two arrays are always equal — so every form saved from the
 * Formio builder came out <b>without</b> a carrier, and the component then failed closed with
 * "… only available from within a user task".
 *
 * <p>Formio can do this safely for its own components because the class re-applies its defaults
 * at runtime. Valtimo's prefill cannot: it runs <b>server-side against the stored JSON</b>, where
 * no component class exists to re-apply anything. So the carrier has to survive serialization.
 *
 * <p>Re-adding it after the filter also stays correct if the builder ever persists its raw form
 * instead of {@code instance.schema} — that object already carries it via Formio's
 * {@code defaultsDeep}. Apply this to <b>every</b> task-bound component; see
 * {@code docs/formio-components.md}.
 *
 * <p>Implementation note: this hooks {@code getModifiedSchema} rather than the {@code schema}
 * getter that calls it, because Valtimo types {@code schema} as a property — overriding it with
 * an accessor is a TypeScript error (TS2611). {@code getModifiedSchema} is the filter itself and
 * is dispatched dynamically from {@code get schema()}, so the effect is the same. Its only
 * callers are that getter and its own recursion, which is why the carrier is appended only on
 * the top-level (non-recursive) pass.
 */
export function withPrefilledTaskIdCarrier(
  BaseComponent: ValtimoFormioComponentConstructor,
): ValtimoFormioComponentConstructor {
  class WithPrefilledTaskIdCarrier extends BaseComponent {
    getModifiedSchema(schema: any, defaultSchema: any, recursion: boolean): any {
      const modified = super.getModifiedSchema(schema, defaultSchema, recursion);
      if (!recursion) {
        modified.components = ensureTaskIdCarrier(modified.components);
      }
      return modified;
    }
  }

  return WithPrefilledTaskIdCarrier;
}

/**
 * Registers an Angular custom element through Valtimo and optionally replaces
 * its Formio implementation with a subclass of Valtimo's public bridge class.
 *
 * Keep all direct access to Formio's global component registry in this adapter.
 */
export function registerEpistolaFormioComponent(
  options: FormioCustomComponentInfo,
  angularComponent: Type<unknown>,
  injector: Injector,
  enhance?: ValtimoFormioComponentEnhancer,
): void {
  registerCustomFormioComponent(options, angularComponent, injector);

  if (!enhance) {
    return;
  }

  const baseComponent = createCustomFormioComponent(options);
  Components.setComponent(options.type, enhance(baseComponent));
}
