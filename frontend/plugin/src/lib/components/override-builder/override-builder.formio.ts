import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaOverrideBuilderComponent } from './override-builder.component';

export const EPISTOLA_OVERRIDE_BUILDER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-override-builder',
  selector: 'epistola-override-builder-element',
  title: 'Epistola Override Builder',
  group: 'basic',
  icon: 'list',
  emptyValue: null,
  fieldOptions: ['label'],
};

export function registerEpistolaOverrideBuilderComponent(injector: Injector): void {
  if (!customElements.get(EPISTOLA_OVERRIDE_BUILDER_OPTIONS.selector)) {
    registerCustomFormioComponent(
      EPISTOLA_OVERRIDE_BUILDER_OPTIONS,
      EpistolaOverrideBuilderComponent,
      injector,
    );
  }
}
