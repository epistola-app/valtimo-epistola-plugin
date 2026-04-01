import {ENVIRONMENT_INITIALIZER, inject, Injector, ModuleWithProviders, NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpClientModule} from '@angular/common/http';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, InputModule, SelectModule} from '@valtimo/components';
import {EpistolaConfigurationComponent} from './components/epistola-configuration/epistola-configuration.component';
import {
  GenerateDocumentConfigurationComponent
} from './components/generate-document-configuration/generate-document-configuration.component';
import {
  CheckJobStatusConfigurationComponent
} from './components/check-job-status-configuration/check-job-status-configuration.component';
import {
  DownloadDocumentConfigurationComponent
} from './components/download-document-configuration/download-document-configuration.component';
import {DataMappingTreeComponent} from './components/data-mapping-tree/data-mapping-tree.component';
import {FieldTreeComponent} from './components/field-tree/field-tree.component';
import {ValueInputComponent} from './components/value-input/value-input.component';
import {ScalarFieldComponent} from './components/scalar-field/scalar-field.component';
import {ArrayFieldComponent} from './components/array-field/array-field.component';
import {EpistolaDownloadComponent} from './components/epistola-download/epistola-download.component';
import {EpistolaRetryFormComponent} from './components/epistola-retry-form/epistola-retry-form.component';
import {EpistolaPreviewButtonComponent} from './components/epistola-preview-button/epistola-preview-button.component';
import {EpistolaDocumentPreviewComponent} from './components/epistola-document-preview/epistola-document-preview.component';
import {EpistolaPluginService} from './services';
import {registerEpistolaDownloadComponent} from './components/epistola-download/epistola-download.formio';
import {registerEpistolaRetryFormComponent} from './components/epistola-retry-form/epistola-retry-form.formio';
import {registerEpistolaPreviewButtonComponent} from './components/epistola-preview-button/epistola-preview-button.formio';
import {registerEpistolaDocumentPreviewComponent} from './components/epistola-document-preview/epistola-document-preview.formio';

@NgModule({
  imports: [
    CommonModule,
    HttpClientModule,
    PluginTranslatePipeModule,
    FormModule,
    InputModule,
    SelectModule,
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    CheckJobStatusConfigurationComponent,
    DownloadDocumentConfigurationComponent,
    DataMappingTreeComponent,
    ValueInputComponent,
    ScalarFieldComponent,
    ArrayFieldComponent,
    FieldTreeComponent,
    EpistolaDownloadComponent,
    EpistolaRetryFormComponent,
    EpistolaPreviewButtonComponent,
    EpistolaDocumentPreviewComponent
  ],
  exports: [
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    CheckJobStatusConfigurationComponent,
    DownloadDocumentConfigurationComponent,
    DataMappingTreeComponent,
    ValueInputComponent,
    ScalarFieldComponent,
    ArrayFieldComponent,
    FieldTreeComponent,
    EpistolaDownloadComponent,
    EpistolaRetryFormComponent,
    EpistolaPreviewButtonComponent,
    EpistolaDocumentPreviewComponent
  ],
  providers: [
    EpistolaPluginService
  ]
})
export class EpistolaPluginModule {
  static forRoot(): ModuleWithProviders<EpistolaPluginModule> {
    return {
      ngModule: EpistolaPluginModule,
      providers: [
        {
          provide: ENVIRONMENT_INITIALIZER,
          multi: true,
          useValue: () => {
            const injector = inject(Injector);
            registerEpistolaDownloadComponent(injector);
            registerEpistolaRetryFormComponent(injector);
            registerEpistolaPreviewButtonComponent(injector);
            registerEpistolaDocumentPreviewComponent(injector);
          }
        }
      ]
    };
  }
}
