import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaDocumentComponent } from './epistola-document.component';

export const EPISTOLA_DOCUMENT_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-document',
  selector: 'epistola-document-element',
  title: 'Epistola Document',
  group: 'basic',
  icon: 'file-pdf-o',
  emptyValue: null,
  fieldOptions: ['label', 'display', 'documentVariable', 'tenantIdVariable', 'filename'],
};

export function registerEpistolaDocumentComponent(injector: Injector): void {
  if (customElements.get(EPISTOLA_DOCUMENT_OPTIONS.selector)) {
    return;
  }
  registerCustomFormioComponent(EPISTOLA_DOCUMENT_OPTIONS, EpistolaDocumentComponent, injector);
}
