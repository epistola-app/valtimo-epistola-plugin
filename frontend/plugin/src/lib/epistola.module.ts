import {NgModule} from '@angular/core';
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
import {EpistolaPluginService} from './services';

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
    FieldTreeComponent
  ],
  exports: [
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    CheckJobStatusConfigurationComponent,
    DownloadDocumentConfigurationComponent,
    DataMappingTreeComponent,
    FieldTreeComponent
  ],
  providers: [
    EpistolaPluginService
  ]
})
export class EpistolaPluginModule {}
