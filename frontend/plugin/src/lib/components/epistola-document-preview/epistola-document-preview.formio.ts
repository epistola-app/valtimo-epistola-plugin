import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaDocumentPreviewComponent } from './epistola-document-preview.component';

export const EPISTOLA_DOCUMENT_PREVIEW_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-document-preview',
  selector: 'epistola-document-preview-element',
  title: 'Epistola Document Preview',
  group: 'basic',
  icon: 'file-pdf-o',
  emptyValue: null,
  fieldOptions: ['label'],
};

export function registerEpistolaDocumentPreviewComponent(injector: Injector): void {
  if (!customElements.get(EPISTOLA_DOCUMENT_PREVIEW_OPTIONS.selector)) {
    registerCustomFormioComponent(
      EPISTOLA_DOCUMENT_PREVIEW_OPTIONS,
      EpistolaDocumentPreviewComponent,
      injector,
    );
  }
}
