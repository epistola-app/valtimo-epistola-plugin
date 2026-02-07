import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpClientModule} from '@angular/common/http';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, InputModule, SelectModule} from '@valtimo/components';
import {EpistolaConfigurationComponent} from './components/epistola-configuration/epistola-configuration.component';
import {
  GenerateDocumentConfigurationComponent
} from './components/generate-document-configuration/generate-document-configuration.component';
import {DataMappingBuilderComponent} from './components/data-mapping-builder/data-mapping-builder.component';
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
    DataMappingBuilderComponent
  ],
  exports: [
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent,
    DataMappingBuilderComponent
  ],
  providers: [
    EpistolaPluginService
  ]
})
export class EpistolaPluginModule {}
