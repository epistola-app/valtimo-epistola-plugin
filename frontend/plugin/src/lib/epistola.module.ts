import {
  ENVIRONMENT_INITIALIZER,
  inject,
  Injector,
  ModuleWithProviders,
  NgModule,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HTTP_INTERCEPTORS, HttpClientModule } from '@angular/common/http';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { FormModule, InputModule, SelectModule } from '@valtimo/components';
import { EpistolaConfigurationComponent } from './components/epistola-configuration/epistola-configuration.component';
import { GenerateDocumentConfigurationComponent } from './components/generate-document-configuration/generate-document-configuration.component';
import { CheckJobStatusConfigurationComponent } from './components/check-job-status-configuration/check-job-status-configuration.component';
import { DownloadDocumentConfigurationComponent } from './components/download-document-configuration/download-document-configuration.component';
import { EpistolaDownloadComponent } from './components/epistola-download/epistola-download.component';
import { EpistolaRetryFormComponent } from './components/epistola-retry-form/epistola-retry-form.component';
import { EpistolaDocumentPreviewComponent } from './components/epistola-document-preview/epistola-document-preview.component';
import { EpistolaGeneratedDocumentPreviewComponent } from './components/epistola-generated-document-preview/epistola-generated-document-preview.component';
import { EpistolaAdminPageComponent } from './components/epistola-admin-page/epistola-admin-page.component';
import {
  EpistolaPluginService,
  EpistolaAdminService,
  EpistolaMenuService,
  EpistolaTaskContextInterceptor,
} from './services';
import { EpistolaAdminRoutingModule } from './epistola-admin-routing.module';
import { isEpistolaEnabled } from './epistola-runtime-config';
import { registerEpistolaDownloadComponent } from './components/epistola-download/epistola-download.formio';
import { registerEpistolaRetryFormComponent } from './components/epistola-retry-form/epistola-retry-form.formio';
import { registerEpistolaDocumentPreviewComponent } from './components/epistola-document-preview/epistola-document-preview.formio';
import { registerEpistolaGeneratedDocumentPreviewComponent } from './components/epistola-generated-document-preview/epistola-generated-document-preview.formio';
import { registerEpistolaOverrideBuilderComponent } from './components/override-builder/override-builder.formio';
import { registerEpistolaProcessLinkSelectorComponent } from './components/process-link-selector/process-link-selector.formio';

@NgModule({
  imports: [
    CommonModule,
    HttpClientModule,
    PluginTranslatePipeModule,
    FormModule,
    InputModule,
    SelectModule,
    EpistolaAdminRoutingModule,
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    CheckJobStatusConfigurationComponent,
    DownloadDocumentConfigurationComponent,
    EpistolaDownloadComponent,
    EpistolaRetryFormComponent,
    EpistolaDocumentPreviewComponent,
    EpistolaGeneratedDocumentPreviewComponent,
    EpistolaAdminPageComponent,
  ],
  exports: [
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    CheckJobStatusConfigurationComponent,
    DownloadDocumentConfigurationComponent,
    EpistolaDownloadComponent,
    EpistolaRetryFormComponent,
    EpistolaDocumentPreviewComponent,
    EpistolaGeneratedDocumentPreviewComponent,
    EpistolaAdminPageComponent,
  ],
  providers: [EpistolaPluginService, EpistolaAdminService],
})
export class EpistolaPluginModule {
  static forRoot(): ModuleWithProviders<EpistolaPluginModule> {
    return {
      ngModule: EpistolaPluginModule,
      providers: [
        EpistolaMenuService,
        {
          provide: HTTP_INTERCEPTORS,
          useClass: EpistolaTaskContextInterceptor,
          multi: true,
        },
        {
          provide: ENVIRONMENT_INITIALIZER,
          multi: true,
          useValue: () => {
            if (!isEpistolaEnabled()) return;
            const injector = inject(Injector);
            registerEpistolaDownloadComponent(injector);
            registerEpistolaRetryFormComponent(injector);
            registerEpistolaOverrideBuilderComponent(injector);
            registerEpistolaProcessLinkSelectorComponent(injector);
            registerEpistolaDocumentPreviewComponent(injector);
            registerEpistolaGeneratedDocumentPreviewComponent(injector);
            // Eagerly create EpistolaMenuService to trigger menu registration
            inject(EpistolaMenuService);
          },
        },
      ],
    };
  }
}
