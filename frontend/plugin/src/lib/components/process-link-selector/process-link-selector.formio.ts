import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaProcessLinkSelectorComponent } from './process-link-selector.component';

export const EPISTOLA_PROCESS_LINK_SELECTOR_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-process-link-selector',
  selector: 'epistola-process-link-selector-element',
  title: 'Epistola Process Link Selector',
  group: 'basic',
  icon: 'link',
  emptyValue: null,
  fieldOptions: ['label'],
};

export function registerEpistolaProcessLinkSelectorComponent(injector: Injector): void {
  if (!customElements.get(EPISTOLA_PROCESS_LINK_SELECTOR_OPTIONS.selector)) {
    registerCustomFormioComponent(
      EPISTOLA_PROCESS_LINK_SELECTOR_OPTIONS,
      EpistolaProcessLinkSelectorComponent,
      injector,
    );
  }
}
