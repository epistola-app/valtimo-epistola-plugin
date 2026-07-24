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

import { Injectable, Injector } from '@angular/core';
import { registerEpistolaDocumentComponent } from '../components/epistola-document/epistola-document.formio';
import { registerEpistolaDocumentPreviewComponent } from '../components/epistola-document-preview/epistola-document-preview.formio';
import { registerEpistolaRetryFormComponent } from '../components/epistola-retry-form/epistola-retry-form.formio';
import { registerEpistolaOverrideBuilderComponent } from '../components/override-builder/override-builder.formio';
import { registerEpistolaProcessLinkSelectorComponent } from '../components/process-link-selector/process-link-selector.formio';
import { isEpistolaEnabled } from '../epistola-runtime-config';
import { EpistolaMenuService } from './epistola-menu.service';

@Injectable()
export class EpistolaRegistrationService {
  private registered = false;

  constructor(
    private readonly injector: Injector,
    private readonly menuService: EpistolaMenuService,
  ) {}

  register(): void {
    if (this.registered || !isEpistolaEnabled()) {
      return;
    }

    this.registered = true;
    this.menuService.register();
    registerEpistolaDocumentComponent(this.injector);
    registerEpistolaRetryFormComponent(this.injector);
    registerEpistolaOverrideBuilderComponent(this.injector);
    registerEpistolaProcessLinkSelectorComponent(this.injector);
    registerEpistolaDocumentPreviewComponent(this.injector);
  }
}
