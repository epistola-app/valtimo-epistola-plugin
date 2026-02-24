import {Injector} from '@angular/core';
import {FormioCustomComponentInfo, registerCustomFormioComponent} from '@valtimo/components';
import {EpistolaDownloadComponent} from './epistola-download.component';

export const EPISTOLA_DOWNLOAD_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-download',
  selector: 'epistola-download-button',
  title: 'Epistola Download',
  group: 'basic',
  icon: 'download',
  emptyValue: null,
  fieldOptions: ['filename', 'label'],
};

export function registerEpistolaDownloadComponent(injector: Injector): void {
  if (!customElements.get(EPISTOLA_DOWNLOAD_OPTIONS.selector)) {
    registerCustomFormioComponent(EPISTOLA_DOWNLOAD_OPTIONS, EpistolaDownloadComponent, injector);
  }
}
