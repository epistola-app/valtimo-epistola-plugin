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
  function createComponent(initial: any = null, overrides: any = {}) {
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
      ...overrides,
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
    // Two catalogs, so nothing is chosen for the author and the cleared state is observable.
    const { component, service } = createComponent(null, {
      getCatalogs: jest.fn(() =>
        of([
          { id: 'gemeente', name: 'Gemeente', type: 'default' },
          { id: 'provincie', name: 'Provincie', type: 'default' },
        ]),
      ),
    });

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
    const { component } = createComponent(
      {
        pluginConfigurationId: 'config-1',
        catalogId: 'gemeente',
        templates: [{ templateId: 'besluit', label: 'Besluit' }],
      },
      {
        getCatalogs: jest.fn(() =>
          of([
            { id: 'gemeente', name: 'Gemeente', type: 'default' },
            { id: 'provincie', name: 'Provincie', type: 'default' },
          ]),
        ),
      },
    );

    component.onConfigurationSelected('config-2');

    expect(component.value).toEqual({
      pluginConfigurationId: 'config-2',
      catalogId: null,
      templates: [],
    });
  });

  /**
   * A normal install has one connection and one catalog, so the author met two dropdowns holding
   * one entry each before reaching a real decision. What matters is that the choice is *emitted*:
   * a selection showing in the UI without being stored is this widget's classic silent failure.
   */
  describe('what reads this letter', () => {
    const FORM_BUILDER_URL = '/case-management/case/correspondentie/version/1.0.0/forms/abc';

    function onBuilderPage(pathname: string = FORM_BUILDER_URL): void {
      // The case type is taken from the form builder's own URL; there is no cleaner route in a
      // plain page. These tests run in Jest's node environment, where there is no window at all —
      // which is also why the component guards for its absence rather than assuming a browser.
      (globalThis as any).window = { location: { pathname } };
    }

    afterEach(() => {
      delete (globalThis as any).window;
    });

    function withReaders(readers: unknown, overrides: any = {}) {
      onBuilderPage();
      const { component, service } = createComponent(null, {
        getLetterReaders: jest.fn(() => readers as any),
        ...overrides,
      });
      component.composerKey = 'pv:epistolaLetter';
      component.ngOnChanges({ composerKey: {} } as any);
      return { component, service };
    }

    it('names the generate tasks that read it', () => {
      const { component, service } = withReaders(
        of([
          {
            processDefinitionKey: 'correspondentie-letter-composer',
            processDefinitionName: 'Brief kiezen en genereren',
            activityId: 'generate-chosen-letter',
          },
        ]),
      );

      expect(service.getLetterReaders).toHaveBeenCalledWith('correspondentie', 'pv:epistolaLetter');
      expect(component.letterReaders).toHaveLength(1);
      expect(component.letterReaders?.[0].activityId).toBe('generate-chosen-letter');
    });

    it('reports an empty answer, which is information rather than an error', () => {
      // The case the hint exists for: a renamed key, or a second composer whose generate task was
      // never configured. It must not be dressed up as a failure — generating in another process is
      // a supported design.
      const { component } = withReaders(of([]));

      expect(component.letterReaders).toEqual([]);
      expect(component.error).toBeNull();
    });

    it('stays silent when the lookup fails, rather than claiming nothing reads it', () => {
      // An empty list and an unanswered question look the same on screen otherwise, and the second
      // would accuse a working configuration.
      const { component } = withReaders(throwError(() => new Error('boom')));

      expect(component.letterReaders).toBeNull();
      expect(component.error).toBeNull();
    });

    it('asks again when the author renames the key', () => {
      const { component, service } = withReaders(of([]));

      component.composerKey = 'pv:epistolaAdhocLetter';
      component.ngOnChanges({ composerKey: {} } as any);

      expect(service.getLetterReaders).toHaveBeenLastCalledWith(
        'correspondentie',
        'pv:epistolaAdhocLetter',
      );
    });

    it('asks nothing without a key', () => {
      const { component, service } = withReaders(of([]));
      (service.getLetterReaders as jest.Mock).mockClear();

      component.composerKey = '';
      component.ngOnChanges({ composerKey: {} } as any);

      expect(service.getLetterReaders).not.toHaveBeenCalled();
      expect(component.letterReaders).toBeNull();
    });

    it('asks nothing away from a case page, where there is no case type to ask about', () => {
      onBuilderPage('/plugins');
      const { component, service } = createComponent(null, {
        getLetterReaders: jest.fn(() => of([])),
      });

      component.composerKey = 'pv:epistolaLetter';
      component.ngOnChanges({ composerKey: {} } as any);

      expect(service.getLetterReaders).not.toHaveBeenCalled();
      expect(component.letterReaders).toBeNull();
    });
  });

  describe('choosing when there is nothing to choose between', () => {
    it('takes the only connection and the only catalog, and stores both', () => {
      const { component, service } = createComponent();

      component.ngOnChanges();

      expect(component.value).toEqual({
        pluginConfigurationId: 'config-1',
        catalogId: 'gemeente',
        templates: [],
      });
      expect(service.getTemplates).toHaveBeenCalledWith('config-1', 'gemeente');
    });

    it('leaves the choice to the author when there is more than one', () => {
      const { component } = createComponent(null, {
        getConfigurations: jest.fn(() =>
          of([
            { id: 'config-1', title: 'Productie', tenantId: 'gemeente' },
            { id: 'config-2', title: 'Acceptatie', tenantId: 'gemeente' },
          ]),
        ),
      });

      component.ngOnChanges();

      expect(component.value).toBeNull();
    });

    it('never overwrites a connection the form already names', () => {
      // Including one whose connection has since gone: the author should see that, not have it
      // quietly corrected to whichever connection happens to be the only one left.
      const { component } = createComponent({
        pluginConfigurationId: 'config-removed',
        catalogId: 'gemeente',
        templates: [],
      });

      component.ngOnChanges();

      expect(component.value.pluginConfigurationId).toBe('config-removed');
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
