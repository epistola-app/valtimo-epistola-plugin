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

import { Injector } from '@angular/core';
import { isEpistolaComposerEnabled } from '../epistola-runtime-config';
import { registerEpistolaLetterComposerComponent } from './letter-composer/epistola-letter-composer.formio';
import { registerEpistolaLetterSetBuilderComponent } from './letter-set-builder/letter-set-builder.formio';
import { registerEpistolaMappingFieldComponent } from './mapping-field/mapping-field.formio';
import { registerEpistolaWriteBackBuilderComponent } from './write-back-builder/write-back-builder.formio';

/**
 * Register the letter composer's Form.io components.
 *
 * <p>One entry point so the rest of the plugin knows the composer by a single name: the
 * registration service calls this, and everything else about the feature stays inside this folder.
 *
 * <p>Does nothing when the composer is switched off. Registration is the right chokepoint: Form.io
 * keeps a global component registry, so a component that never registers is absent from the palette,
 * from the builder's edit forms and from any form that renders one — there is no second place to
 * remember. The backend flag of the same name removes the endpoints; without this half an author can
 * configure a composer that has nowhere to call.
 */
export function registerEpistolaComposerComponents(injector: Injector): void {
  if (!isEpistolaComposerEnabled()) {
    return;
  }
  registerEpistolaMappingFieldComponent(injector);
  registerEpistolaLetterSetBuilderComponent(injector);
  registerEpistolaWriteBackBuilderComponent(injector);
  registerEpistolaLetterComposerComponent(injector);
}
