import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, InputModule, SelectModule} from '@valtimo/components';
import {EpistolaConfigurationComponent} from './components/epistola-configuration/epistola-configuration.component';
import {GenerateDocumentConfigurationComponent} from './components/generate-document-configuration/generate-document-configuration.component';

@NgModule({
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
    FormModule,
    InputModule,
    SelectModule,
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent
  ],
  exports: [
    EpistolaConfigurationComponent,
    GenerateDocumentConfigurationComponent
  ],
})
export class EpistolaPluginModule {}
