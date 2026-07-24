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

import {
  inject,
  ModuleWithProviders,
  NgModule,
  provideEnvironmentInitializer,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClientModule } from '@angular/common/http';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { FormIoModule, FormModule, InputModule, SelectModule } from '@valtimo/components';
import { EpistolaConfigurationComponent } from './components/epistola-configuration/epistola-configuration.component';
import { GenerateDocumentConfigurationComponent } from './components/generate-document-configuration/generate-document-configuration.component';
import { CheckJobStatusConfigurationComponent } from './components/check-job-status-configuration/check-job-status-configuration.component';
import { DownloadDocumentConfigurationComponent } from './components/download-document-configuration/download-document-configuration.component';
import { EpistolaDocumentComponent } from './components/epistola-document/epistola-document.component';
import { EpistolaRetryFormComponent } from './components/epistola-retry-form/epistola-retry-form.component';
import { EpistolaDocumentPreviewComponent } from './components/epistola-document-preview/epistola-document-preview.component';
import { EpistolaAdminPageComponent } from './components/epistola-admin-page/epistola-admin-page.component';
import { EpistolaPluginService, EpistolaAdminService, EpistolaMenuService } from './services';
import { EpistolaAdminRoutingModule } from './epistola-admin-routing.module';
import { EpistolaRegistrationService } from './services/epistola-registration.service';

@NgModule({
  imports: [
    CommonModule,
    HttpClientModule,
    FormIoModule,
    PluginTranslatePipeModule,
    FormModule,
    InputModule,
    SelectModule,
    EpistolaAdminRoutingModule,
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    CheckJobStatusConfigurationComponent,
    DownloadDocumentConfigurationComponent,
    EpistolaDocumentComponent,
    EpistolaRetryFormComponent,
    EpistolaDocumentPreviewComponent,
    EpistolaAdminPageComponent,
  ],
  exports: [
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    CheckJobStatusConfigurationComponent,
    DownloadDocumentConfigurationComponent,
    EpistolaDocumentComponent,
    EpistolaRetryFormComponent,
    EpistolaDocumentPreviewComponent,
    EpistolaAdminPageComponent,
  ],
  providers: [
    EpistolaPluginService,
    EpistolaAdminService,
    EpistolaMenuService,
    EpistolaRegistrationService,
    provideEnvironmentInitializer(() => inject(EpistolaRegistrationService).register()),
  ],
})
export class EpistolaPluginModule {
  // Kept for back-compat with hosts that follow the README's `forRoot()`
  // setup. The providers above are now module-level so `imports: [EpistolaPluginModule]`
  // (what the Valtimo Configurator emits) wires everything on its own.
  static forRoot(): ModuleWithProviders<EpistolaPluginModule> {
    return { ngModule: EpistolaPluginModule };
  }
}
