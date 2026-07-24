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

jest.mock('@angular/core', () => ({}));
jest.mock('@valtimo/components', () => ({
  createCustomFormioComponent: jest.fn(),
  registerCustomFormioComponent: jest.fn(),
}));
jest.mock('formiojs', () => ({
  Components: { setComponent: jest.fn() },
}));

import { createCustomFormioComponent, registerCustomFormioComponent } from '@valtimo/components';
import { Components } from 'formiojs';
import { registerEpistolaFormioComponent } from './valtimo-formio-adapter';

const options = {
  type: 'epistola-test',
  selector: 'epistola-test-element',
  title: 'Epistola test',
  group: 'none',
  icon: 'test',
};

describe('registerEpistolaFormioComponent', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('delegates standard registration entirely to Valtimo', () => {
    const angularComponent = class {};
    const injector = {};

    registerEpistolaFormioComponent(options, angularComponent, injector as never);

    expect(registerCustomFormioComponent).toHaveBeenCalledWith(options, angularComponent, injector);
    expect(createCustomFormioComponent).not.toHaveBeenCalled();
    expect(Components.setComponent).not.toHaveBeenCalled();
  });

  it('enhances the Valtimo bridge through the Formio registry', () => {
    class BaseComponent {}
    class EnhancedComponent extends BaseComponent {}
    const enhance = jest.fn(() => EnhancedComponent);
    (createCustomFormioComponent as jest.Mock).mockReturnValue(BaseComponent);

    registerEpistolaFormioComponent(options, class {}, {} as never, enhance as never);

    expect(createCustomFormioComponent).toHaveBeenCalledWith(options);
    expect(enhance).toHaveBeenCalledWith(BaseComponent);
    expect(Components.setComponent).toHaveBeenCalledWith(options.type, EnhancedComponent);
  });
});
