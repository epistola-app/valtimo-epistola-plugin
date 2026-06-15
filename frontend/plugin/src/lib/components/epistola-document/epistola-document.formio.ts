import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaDocumentComponent } from './epistola-document.component';
import { readPrefilledTaskId } from '../../services/prefilled-task-id';

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

  // Extend the base class to forward the server-prefilled task id (epistola-task: value
  // resolver) to the Angular element, so the download authorizes against the exact task in
  // every Valtimo task-open flow — not just the direct-open flow the HTTP interceptor observes.
  const Formio = (window as any).Formio;
  const BaseComponent = Formio?.Components?.components?.[EPISTOLA_DOCUMENT_OPTIONS.type];
  if (!BaseComponent) {
    return;
  }

  class EpistolaDocumentWithTaskContext extends BaseComponent {
    attach(element: HTMLElement) {
      const result = super.attach(element);
      if (this._customAngularElement) {
        const prefilledTaskId = readPrefilledTaskId(this.root);
        if (prefilledTaskId) {
          this._customAngularElement['taskInstanceId'] = prefilledTaskId;
        }
      }
      return result;
    }
  }

  Formio.Components.setComponent(EPISTOLA_DOCUMENT_OPTIONS.type, EpistolaDocumentWithTaskContext);
}
