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

export type ValtimoFormioComponentConstructor = ReturnType<typeof createCustomFormioComponent>;
export type ValtimoFormioComponent = InstanceType<ValtimoFormioComponentConstructor>;
export type ValtimoFormioComponentEnhancer = (
  baseComponent: ValtimoFormioComponentConstructor,
) => ValtimoFormioComponentConstructor;

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
