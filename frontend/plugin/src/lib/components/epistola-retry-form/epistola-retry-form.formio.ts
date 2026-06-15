import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaRetryFormComponent } from './epistola-retry-form.component';
import { readPrefilledTaskId } from '../../services/prefilled-task-id';

export const EPISTOLA_RETRY_FORM_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-retry-form',
  selector: 'epistola-retry-form-element',
  title: 'Epistola Retry Form',
  group: 'basic',
  icon: 'refresh',
  emptyValue: null,
  fieldOptions: ['sourceActivityId', 'label'], // sourceActivityId is optional (set via BPMN input parameter)
};

export function registerEpistolaRetryFormComponent(injector: Injector): void {
  if (customElements.get(EPISTOLA_RETRY_FORM_OPTIONS.selector)) {
    return;
  }
  registerCustomFormioComponent(EPISTOLA_RETRY_FORM_OPTIONS, EpistolaRetryFormComponent, injector);

  // Extend the base class to forward the server-prefilled task id (epistola-task: value
  // resolver) to the Angular element, so the retry form authorizes against the exact task in
  // every Valtimo task-open flow — not just the direct-open flow the HTTP interceptor observes.
  const Formio = (window as any).Formio;
  const BaseComponent = Formio?.Components?.components?.[EPISTOLA_RETRY_FORM_OPTIONS.type];
  if (!BaseComponent) {
    return;
  }

  class EpistolaRetryFormWithTaskContext extends BaseComponent {
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

  Formio.Components.setComponent(
    EPISTOLA_RETRY_FORM_OPTIONS.type,
    EpistolaRetryFormWithTaskContext,
  );
}
