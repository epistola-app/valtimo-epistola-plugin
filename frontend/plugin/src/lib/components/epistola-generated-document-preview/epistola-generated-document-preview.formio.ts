import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaGeneratedDocumentPreviewComponent } from './epistola-generated-document-preview.component';

export const EPISTOLA_GENERATED_DOCUMENT_PREVIEW_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-generated-document-preview',
  selector: 'epistola-generated-document-preview-element',
  title: 'Epistola Generated Document Preview',
  group: 'basic',
  icon: 'file-pdf-o',
  emptyValue: null,
  fieldOptions: ['label', 'documentIdVariable', 'tenantIdVariable'],
};

export function registerEpistolaGeneratedDocumentPreviewComponent(injector: Injector): void {
  if (customElements.get(EPISTOLA_GENERATED_DOCUMENT_PREVIEW_OPTIONS.selector)) {
    return;
  }
  registerCustomFormioComponent(
    EPISTOLA_GENERATED_DOCUMENT_PREVIEW_OPTIONS,
    EpistolaGeneratedDocumentPreviewComponent,
    injector,
  );
}
