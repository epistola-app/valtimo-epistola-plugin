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
jest.mock('@valtimo/plugin', () => ({
  PluginTranslatePipeModule: class {},
  PluginTranslationService: class {},
}));
jest.mock('../composer-api.service', () => ({ EpistolaComposerApiService: class {} }));

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
    const translations = { instant: jest.fn((key: string) => key) };
    const component = new EpistolaLetterSetBuilderComponent(
      service as any,
      cdr as any,
      translations as any,
    );
    component.value = initial;
    return { component, service };
  }

  describe('configuring one letter on its own', () => {
    function offering(...templateIds: string[]) {
      const { component, service } = createComponent({
        pluginConfigurationId: 'config-1',
        catalogId: 'gemeente',
        templates: templateIds.map((templateId) => ({ templateId, label: templateId })),
      });
      component.ngOnChanges();
      return { component, service };
    }

    it('opens for one letter at a time', () => {
      // One panel open at a time, because the settings are per letter and two open panels invite
      // editing the wrong one — the fields look identical.
      const { component } = offering('besluit', 'herinnering');

      component.toggleSettings('besluit');
      expect(component.isSettingsOpen('besluit')).toBe(true);

      component.toggleSettings('herinnering');
      expect(component.isSettingsOpen('herinnering')).toBe(true);
      expect(component.isSettingsOpen('besluit')).toBe(false);

      component.toggleSettings('herinnering');
      expect(component.isSettingsOpen('herinnering')).toBe(false);
    });

    it('stores a mapping fragment against the letter it was written for', () => {
      const { component } = offering('besluit', 'herinnering');

      component.setTemplateMapping('besluit', '{ "aanhef": "Geachte heer" }');

      const stored = component.value?.templates ?? [];
      expect(stored.find((t: any) => t.templateId === 'besluit')?.dataMapping).toBe(
        '{ "aanhef": "Geachte heer" }',
      );
      expect(stored.find((t: any) => t.templateId === 'herinnering')?.dataMapping).toBeUndefined();
    });

    it('drops a fragment that was cleared rather than storing an empty one', () => {
      // An empty string would be a fragment that merges nothing over the baseline, which is the
      // same as having none — but it would also make every saved letter look configured.
      const { component } = offering('besluit');
      component.setTemplateMapping('besluit', '{ "aanhef": "Geachte heer" }');

      component.setTemplateMapping('besluit', '   ');

      expect(component.value?.templates[0].dataMapping).toBeUndefined();
    });

    it('keeps the label when a fragment is written, and the other way round', () => {
      const { component } = offering('besluit');

      component.setTemplateMapping('besluit', '{ "x": 1 }');
      component.setLabel('besluit', 'Besluit op bezwaar');

      const stored = component.value?.templates[0];
      expect(stored.label).toBe('Besluit op bezwaar');
      expect(stored.dataMapping).toBe('{ "x": 1 }');
    });

    it('is not offered for a letter that is not ticked', () => {
      const { component } = offering('besluit');

      expect(component.canConfigure('besluit')).toBe(true);
      expect(component.canConfigure('herinnering')).toBe(false);
    });
  });

  describe('a letter that is no longer in the catalog', () => {
    /**
     * The table is drawn from what Epistola returns now, ticking the letters this composer stores.
     * A stored letter that has been removed is therefore simply not drawn, and the configuration
     * looks healthy while one of its letters is dead — the author finds out when an employee opens
     * the task and the composer refuses it.
     */
    it('is named, so the author can see it at all', () => {
      const { component } = createComponent({
        pluginConfigurationId: 'config-1',
        catalogId: 'gemeente',
        templates: [
          { templateId: 'besluit', label: 'Besluit' },
          { templateId: 'ingetrokken', label: 'Ingetrokken brief' },
        ],
      });
      component.ngOnChanges();

      expect(component.missingTemplates.map((t) => t.templateId)).toEqual(['ingetrokken']);
    });

    it('can be dropped from the set', () => {
      const { component } = createComponent({
        pluginConfigurationId: 'config-1',
        catalogId: 'gemeente',
        templates: [
          { templateId: 'besluit', label: 'Besluit' },
          { templateId: 'ingetrokken', label: 'Ingetrokken brief' },
        ],
      });
      component.ngOnChanges();

      component.toggleTemplate('ingetrokken', false);

      expect(component.value?.templates.map((t: any) => t.templateId)).toEqual(['besluit']);
      expect(component.missingTemplates).toEqual([]);
    });

    it('says nothing when the catalog could not be read', () => {
      // Everything looks missing when the fetch failed, and telling an author that all their
      // letters are gone because Epistola was briefly unreachable is worse than saying nothing.
      const { component, service } = createComponent({
        pluginConfigurationId: 'config-1',
        catalogId: 'gemeente',
        templates: [{ templateId: 'besluit', label: 'Besluit' }],
      });
      // Succeed first, so the answer is not simply "nothing has been loaded yet": this has to stay
      // quiet when a catalog that *was* readable stops being readable.
      component.ngOnChanges();
      expect(component.missingTemplates).toEqual([]);

      service.getTemplates.mockReturnValue(throwError(() => new Error('Epistola unreachable')));
      component.onCatalogSelected('andere');

      expect(component.missingTemplates).toEqual([]);
    });
  });

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
    jest.mock('../composer-api.service', () => ({ EpistolaComposerApiService: class {} }));
    const service = {
      getConfigurations: jest.fn(() => throwError(() => new Error('boom'))),
      getCatalogs: jest.fn(),
      getTemplates: jest.fn(),
    };
    const component = new EpistolaLetterSetBuilderComponent(
      service as any,
      { markForCheck: jest.fn() } as any,
      { instant: jest.fn((key: string) => key) } as any,
    );

    expect(component.error).toBe('letterSetConnectionsFailed');
  });
});
