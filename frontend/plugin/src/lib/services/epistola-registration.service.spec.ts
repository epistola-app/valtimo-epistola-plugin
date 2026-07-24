/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

jest.mock('@angular/core', () => ({ Injectable: () => () => undefined }));
jest.mock('../epistola-runtime-config', () => ({ isEpistolaEnabled: jest.fn() }));
jest.mock('./epistola-menu.service', () => ({ EpistolaMenuService: class {} }));
jest.mock('../components/epistola-document/epistola-document.formio', () => ({
  registerEpistolaDocumentComponent: jest.fn(),
}));
jest.mock('../components/epistola-document-preview/epistola-document-preview.formio', () => ({
  registerEpistolaDocumentPreviewComponent: jest.fn(),
}));
jest.mock('../components/epistola-retry-form/epistola-retry-form.formio', () => ({
  registerEpistolaRetryFormComponent: jest.fn(),
}));
jest.mock('../components/override-builder/override-builder.formio', () => ({
  registerEpistolaOverrideBuilderComponent: jest.fn(),
}));
jest.mock('../components/process-link-selector/process-link-selector.formio', () => ({
  registerEpistolaProcessLinkSelectorComponent: jest.fn(),
}));

import { registerEpistolaDocumentComponent } from '../components/epistola-document/epistola-document.formio';
import { registerEpistolaDocumentPreviewComponent } from '../components/epistola-document-preview/epistola-document-preview.formio';
import { registerEpistolaRetryFormComponent } from '../components/epistola-retry-form/epistola-retry-form.formio';
import { registerEpistolaOverrideBuilderComponent } from '../components/override-builder/override-builder.formio';
import { registerEpistolaProcessLinkSelectorComponent } from '../components/process-link-selector/process-link-selector.formio';
import { isEpistolaEnabled } from '../epistola-runtime-config';
import { EpistolaRegistrationService } from './epistola-registration.service';

const registrationFunctions = [
  registerEpistolaDocumentComponent,
  registerEpistolaRetryFormComponent,
  registerEpistolaOverrideBuilderComponent,
  registerEpistolaProcessLinkSelectorComponent,
  registerEpistolaDocumentPreviewComponent,
] as jest.Mock[];

describe('EpistolaRegistrationService', () => {
  const injector = {};
  const menuService = { register: jest.fn() };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('registers the menu and Formio components once when Epistola is enabled', () => {
    (isEpistolaEnabled as jest.Mock).mockReturnValue(true);
    const service = new EpistolaRegistrationService(injector as never, menuService as never);

    service.register();
    service.register();

    expect(menuService.register).toHaveBeenCalledTimes(1);
    registrationFunctions.forEach((register) => {
      expect(register).toHaveBeenCalledTimes(1);
      expect(register).toHaveBeenCalledWith(injector);
    });
  });

  it('does not register anything while Epistola is disabled', () => {
    (isEpistolaEnabled as jest.Mock).mockReturnValue(false);
    const service = new EpistolaRegistrationService(injector as never, menuService as never);

    service.register();

    expect(menuService.register).not.toHaveBeenCalled();
    registrationFunctions.forEach((register) => expect(register).not.toHaveBeenCalled());
  });
});
