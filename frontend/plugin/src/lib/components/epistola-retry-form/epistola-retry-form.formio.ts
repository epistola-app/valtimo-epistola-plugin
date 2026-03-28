import {Injector} from '@angular/core';
import {FormioCustomComponentInfo, registerCustomFormioComponent} from '@valtimo/components';
import {EpistolaRetryFormComponent} from './epistola-retry-form.component';

export const EPISTOLA_RETRY_FORM_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-retry-form',
  selector: 'epistola-retry-form-element',
  title: 'Epistola Retry Form',
  group: 'basic',
  icon: 'refresh',
  emptyValue: null,
  fieldOptions: ['sourceActivityId', 'label'],  // sourceActivityId is optional (set via BPMN input parameter)
};

export function registerEpistolaRetryFormComponent(injector: Injector): void {
  if (!customElements.get(EPISTOLA_RETRY_FORM_OPTIONS.selector)) {
    registerCustomFormioComponent(EPISTOLA_RETRY_FORM_OPTIONS, EpistolaRetryFormComponent, injector);
  }
}
