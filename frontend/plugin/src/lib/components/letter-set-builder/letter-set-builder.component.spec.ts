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

jest.mock('@angular/core', () => ({
  ChangeDetectionStrategy: { OnPush: 'OnPush' },
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
  Output: () => () => undefined,
  EventEmitter: class {
    emit = jest.fn();
  },
}));

jest.mock('@angular/common', () => ({ CommonModule: class {} }));
jest.mock('@angular/forms', () => ({ FormsModule: class {} }));
jest.mock('@valtimo/components', () => ({}));
jest.mock('../../services', () => ({ EpistolaPluginService: class {} }));

import { of, throwError } from 'rxjs';
import { EpistolaLetterSetBuilderComponent } from './letter-set-builder.component';

describe('EpistolaLetterSetBuilderComponent', () => {
  function createComponent(initial: any = null) {
    const service = {
      getConfigurations: jest.fn(() =>
        of([{ id: 'config-1', title: 'Epistola productie', tenantId: 'gemeente' }]),
      ),
      getCatalogs: jest.fn(() => of([{ id: 'gemeente', name: 'Gemeente', type: 'default' }])),
      getTemplates: jest.fn(() =>
        of([
          { id: 'besluit', name: 'Besluit op bezwaar', catalogId: 'gemeente' },
          { id: 'herinnering', name: 'Herinnering', catalogId: 'gemeente' },
        ]),
      ),
    };
    const cdr = { markForCheck: jest.fn() };
    const component = new EpistolaLetterSetBuilderComponent(service as any, cdr as any);
    component.value = initial;
    return { component, service };
  }

  it('offers the configured Epistola connections', () => {
    const { component } = createComponent();

    expect(component.configurations).toEqual([
      { id: 'config-1', title: 'Epistola productie', tenantId: 'gemeente' },
    ]);
  });

  it('loads the catalogs of the chosen connection', () => {
    const { component, service } = createComponent();

    component.onConfigurationSelected('config-1');

    expect(service.getCatalogs).toHaveBeenCalledWith('config-1');
    expect(component.value).toEqual({
      pluginConfigurationId: 'config-1',
      catalogId: null,
      templates: [],
    });
  });

  it('clears the catalog and letters when the connection changes', () => {
    // Those ids mean nothing in another connection, so keeping them would fail only at runtime.
    const { component } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: 'gemeente',
      templates: [{ templateId: 'besluit', label: 'Besluit' }],
    });

    component.onConfigurationSelected('config-2');

    expect(component.value).toEqual({
      pluginConfigurationId: 'config-2',
      catalogId: null,
      templates: [],
    });
  });

  it('loads the templates of the chosen catalog', () => {
    const { component, service } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: null,
      templates: [],
    });

    component.onCatalogSelected('gemeente');

    expect(service.getTemplates).toHaveBeenCalledWith('config-1', 'gemeente');
    expect(component.value?.catalogId).toBe('gemeente');
  });

  it('ticks a letter with the template name as its default label', () => {
    const { component } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: 'gemeente',
      templates: [],
    });
    component.onCatalogSelected('gemeente');

    component.toggleTemplate('besluit', true);

    expect(component.value?.templates).toEqual([
      { templateId: 'besluit', label: 'Besluit op bezwaar' },
    ]);
    expect(component.isOffered('besluit')).toBe(true);
  });

  it('unticks a letter without touching the others', () => {
    const { component } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: 'gemeente',
      templates: [
        { templateId: 'besluit', label: 'Besluit' },
        { templateId: 'herinnering', label: 'Herinnering' },
      ],
    });

    component.toggleTemplate('besluit', false);

    expect(component.value?.templates).toEqual([
      { templateId: 'herinnering', label: 'Herinnering' },
    ]);
  });

  it('renames the label an employee sees', () => {
    const { component } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: 'gemeente',
      templates: [{ templateId: 'besluit', label: 'Besluit' }],
    });

    component.setLabel('besluit', 'Besluit op uw bezwaar');

    expect(component.labelOf('besluit')).toBe('Besluit op uw bezwaar');
  });

  it('continues the cascade from what was already saved', () => {
    const { component, service } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: 'gemeente',
      templates: [{ templateId: 'besluit', label: 'Besluit' }],
    });

    // Formio sets the saved value after construction, so the restore hangs off the input change.
    component.ngOnChanges();

    expect(service.getCatalogs).toHaveBeenCalledWith('config-1');
    expect(service.getTemplates).toHaveBeenCalledWith('config-1', 'gemeente');
  });

  it('restores only once, so a later change does not refetch everything', () => {
    const { component, service } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: 'gemeente',
      templates: [],
    });

    component.ngOnChanges();
    component.ngOnChanges();

    expect(service.getCatalogs).toHaveBeenCalledTimes(1);
  });

  it('reports a failed lookup instead of silently offering nothing', () => {
    jest.mock('../../services', () => ({ EpistolaPluginService: class {} }));
    const service = {
      getConfigurations: jest.fn(() => throwError(() => new Error('boom'))),
      getCatalogs: jest.fn(),
      getTemplates: jest.fn(),
    };
    const component = new EpistolaLetterSetBuilderComponent(
      service as any,
      {
        markForCheck: jest.fn(),
      } as any,
    );

    expect(component.error).toBe('Could not load the Epistola connections.');
  });
});
