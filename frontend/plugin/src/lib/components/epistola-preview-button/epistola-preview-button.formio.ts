import {Injector} from '@angular/core';
import {FormioCustomComponentInfo, registerCustomFormioComponent} from '@valtimo/components';
import {EpistolaPreviewButtonComponent} from './epistola-preview-button.component';

export const EPISTOLA_PREVIEW_BUTTON_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-preview-button',
  selector: 'epistola-preview-button-element',
  title: 'Epistola Preview',
  group: 'basic',
  icon: 'eye',
  emptyValue: null,
  fieldOptions: ['label'],
};

export function registerEpistolaPreviewButtonComponent(injector: Injector): void {
  if (!customElements.get(EPISTOLA_PREVIEW_BUTTON_OPTIONS.selector)) {
    registerCustomFormioComponent(EPISTOLA_PREVIEW_BUTTON_OPTIONS, EpistolaPreviewButtonComponent, injector);
  }
}
