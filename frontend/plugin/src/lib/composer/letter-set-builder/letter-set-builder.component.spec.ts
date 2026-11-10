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
import { ANY_ENVIRONMENT, ANY_RANGE } from '../../services/release-scope';

/** The selection the fake design scope hands out, which the letters must then be read with. */
const read = (environmentId: string | null = null, versionRange = '2.1.0') => ({
  environmentId,
  versionRange,
});

describe('EpistolaLetterSetBuilderComponent', () => {
  function createComponent(initial: any = null, overrides: any = {}) {
    const service = {
      getConfigurations: jest.fn(() =>
        of([{ id: 'config-1', title: 'Epistola productie', tenantId: 'gemeente' }]),
      ),
      getCatalogs: jest.fn(() =>
        of([
          {
            id: 'local/valtimo/gemeente',
            name: 'Gemeente',
            registry: 'local',
            namespace: 'valtimo',
            key: 'gemeente',
            editable: true,
          },
        ]),
      ),
      getTemplates: jest.fn(() =>
        of([
          { id: 'besluit', name: 'Besluit op bezwaar', catalogId: 'gemeente' },
          { id: 'herinnering', name: 'Herinnering', catalogId: 'gemeente' },
        ]),
      ),
      getEnvironments: jest.fn(() =>
        of([
          { id: 'productie', name: 'Productie' },
          { id: 'acceptatie', name: 'Acceptatie' },
        ]),
      ),
      // Like the backend: the highest release, or 2.0.0 for a literal range, read as an exact
      // version in the environment it was read in.
      getDesignScope: jest.fn((_c: string, catalog: string, env: unknown, range: unknown) => {
        const environment = typeof env === 'string' ? env : null;
        const version = typeof range === 'string' ? '2.0.0' : '2.1.0';
        return of({
          catalogId: catalog,
          versions: ['2.1.0', '2.0.0'],
          version,
          draft: false,
          environment,
          problems: [],
          read: { environmentId: environment, versionRange: version },
        });
      }),
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

  describe('a set saved against contract 1.x', () => {
    // It holds the catalog's key alone; catalogs are listed as `registry/namespace/key` since 2.0.
    it('shows its catalog as selected', () => {
      const { component } = createComponent({
        pluginConfigurationId: 'config-1',
        catalogId: 'gemeente',
        templates: [{ templateId: 'besluit', label: 'Besluit' }],
      });
      component.ngOnChanges();

      expect(component.selectedCatalogId).toBe('local/valtimo/gemeente');
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
          {
            id: 'local/valtimo/gemeente',
            name: 'Gemeente',
            registry: 'local',
            namespace: 'valtimo',
            key: 'gemeente',
            editable: true,
          },
          {
            id: 'local/valtimo/provincie',
            name: 'Provincie',
            registry: 'local',
            namespace: 'valtimo',
            key: 'provincie',
            editable: true,
          },
        ]),
      ),
    });

    component.onConfigurationSelected('config-1');

    expect(service.getCatalogs).toHaveBeenCalledWith('config-1', undefined);
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
            {
              id: 'local/valtimo/gemeente',
              name: 'Gemeente',
              registry: 'local',
              namespace: 'valtimo',
              key: 'gemeente',
              editable: true,
            },
            {
              id: 'local/valtimo/provincie',
              name: 'Provincie',
              registry: 'local',
              namespace: 'valtimo',
              key: 'provincie',
              editable: true,
            },
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
        catalogId: 'local/valtimo/gemeente',
        templates: [],
      });
      expect(service.getTemplates).toHaveBeenCalledWith(
        'config-1',
        'local/valtimo/gemeente',
        read(),
      );
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

    expect(service.getDesignScope).toHaveBeenCalledWith(
      'config-1',
      'gemeente',
      undefined,
      undefined,
    );
    expect(service.getTemplates).toHaveBeenCalledWith('config-1', 'gemeente', read());
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

  describe('where the letters render', () => {
    const saved = () => ({
      pluginConfigurationId: 'config-1',
      catalogId: 'local/valtimo/gemeente',
      templates: [{ templateId: 'besluit', label: 'Besluit' }],
    });

    it('lists the environments of the connection, and reads catalogs and letters for the chosen one', () => {
      const { component, service } = createComponent(saved());
      component.ngOnChanges();

      component.onEnvironmentSelected('acceptatie');

      expect(component.environments.map((environment) => environment.id)).toEqual([
        'productie',
        'acceptatie',
      ]);
      // Stored as JSONata since schema 4: the literal naming it (ADR 0009).
      expect(component.value?.environmentId).toBe('"acceptatie"');
      expect(component.environmentSelection).toBe('acceptatie');
      expect(service.getCatalogs).toHaveBeenLastCalledWith('config-1', 'acceptatie');
      expect(service.getTemplates).toHaveBeenLastCalledWith(
        'config-1',
        'local/valtimo/gemeente',
        read('acceptatie'),
      );
      // Another environment is not another set of letters: what the author chose stays.
      expect(component.value?.catalogId).toBe('local/valtimo/gemeente');
      expect(component.value?.templates).toEqual([{ templateId: 'besluit', label: 'Besluit' }]);
    });

    it('takes an environment expression, decided per case, and reads catalog-wide for it', () => {
      const { component, service } = createComponent(saved());
      component.ngOnChanges();

      component.onEnvironmentSelected(component.EXPRESSION);
      expect(component.environmentExpressionMode).toBe(true);
      expect(component.value).not.toHaveProperty('environmentId');

      component.onEnvironmentExpressionChange(' $pv.omgeving ');

      expect(component.value?.environmentId).toBe('$pv.omgeving');
      expect(service.getCatalogs).toHaveBeenLastCalledWith('config-1', ANY_ENVIRONMENT);
      expect(service.getDesignScope).toHaveBeenLastCalledWith(
        'config-1',
        'local/valtimo/gemeente',
        ANY_ENVIRONMENT,
        undefined,
      );
      expect(component.designHints.release).toContainEqual({
        key: 'designBasedOnAssumption',
        problem: false,
      });
    });

    it('reopens a stored expression as one', () => {
      const { component } = createComponent({ ...saved(), environmentId: '$pv.omgeving' });
      component.ngOnChanges();

      expect(component.environmentSelection).toBe(component.EXPRESSION);
    });

    it('stores no environment at all for the default, as a set saved before the field did', () => {
      const { component } = createComponent({ ...saved(), environmentId: '"acceptatie"' });
      component.ngOnChanges();

      component.onEnvironmentSelected('');

      expect(component.value).not.toHaveProperty('environmentId');
    });

    it('keeps a catalog that is gone, and says so', () => {
      const { component } = createComponent(
        { ...saved(), catalogId: 'local/valtimo/provincie' },
        {},
      );
      component.ngOnChanges();

      expect(component.catalogNotListed).toBe(true);
      expect(component.selectedCatalogId).toBe('local/valtimo/provincie');
      expect(component.value?.catalogId).toBe('local/valtimo/provincie');
    });

    it('warns once about a catalog the environment does not serve, and still lists its letters', () => {
      const { component, service } = createComponent(
        { ...saved(), environmentId: '"acceptatie"' },
        {
          getDesignScope: jest.fn(() =>
            of({
              catalogId: 'local/valtimo/gemeente',
              versions: ['2.1.0'],
              version: '2.1.0',
              draft: false,
              environment: null,
              problems: ['NOT_DEPLOYED'],
              read: { environmentId: null, versionRange: '2.1.0' },
            }),
          ),
        },
      );
      component.ngOnChanges();

      expect(component.designHints.catalog).toEqual([
        { key: 'designCatalogNotDeployed', problem: true },
      ]);
      expect(service.getTemplates).toHaveBeenLastCalledWith(
        'config-1',
        'local/valtimo/gemeente',
        read(),
      );
      expect(component.templates.map((template) => template.id)).toEqual([
        'besluit',
        'herinnering',
      ]);
      expect(component.error).toBeNull();
    });

    it('pins the set to a release, reads its letters from it, and says which release it is', () => {
      const { component, service } = createComponent(saved());
      component.ngOnChanges();

      component.onVersionRangeChange(' ^2.0.0 ');

      // Typed as a range, stored as the literal; shown as typed.
      expect(component.value?.versionRange).toBe('"^2.0.0"');
      expect(component.rangeText(component.SET_RANGE, component.value?.versionRange)).toBe(
        '^2.0.0',
      );
      expect(service.getDesignScope).toHaveBeenLastCalledWith(
        'config-1',
        'local/valtimo/gemeente',
        undefined,
        '^2.0.0',
      );
      expect(service.getTemplates).toHaveBeenLastCalledWith(
        'config-1',
        'local/valtimo/gemeente',
        read(null, '2.0.0'),
      );
      expect(component.designHints.release).toEqual([
        { key: 'designBasedOnRelease', version: '2.0.0', problem: false },
      ]);
      expect(component.releaseVersions).toEqual(['2.1.0', '2.0.0']);
    });

    it('takes a range expression once switched to one, and clears it switching back', () => {
      const { component, service } = createComponent(saved());
      component.ngOnChanges();

      component.toggleSetRangeExpression(true);
      component.onVersionRangeChange('$pv.briefversie');

      expect(component.value?.versionRange).toBe('$pv.briefversie');
      expect(service.getDesignScope).toHaveBeenLastCalledWith(
        'config-1',
        'local/valtimo/gemeente',
        undefined,
        ANY_RANGE,
      );

      // A literal survives the switch back; an expression has no range to show and is cleared.
      component.toggleSetRangeExpression(false);
      expect(component.value).not.toHaveProperty('versionRange');
    });

    it('lets the range hint explain a range no release satisfies, not a failed catalog read', () => {
      const { component } = createComponent(saved(), {
        getDesignScope: jest.fn(() =>
          of({
            catalogId: 'local/valtimo/gemeente',
            versions: ['1.4.0'],
            version: '1.4.0',
            draft: false,
            environment: null,
            problems: ['RANGE_UNSATISFIED'],
            read: { environmentId: null, versionRange: '1.4.0' },
          }),
        ),
      });
      component.ngOnChanges();

      component.onVersionRangeChange('^3.0.0');

      expect(component.error).toBeNull();
      expect(component.designHints.release[0]).toEqual({
        key: 'designRangeUnsatisfied',
        problem: true,
      });
    });

    it('says only that a range is not one, without also reporting a failed catalog read', () => {
      const { component, service } = createComponent(saved(), {
        getDesignScope: jest.fn(() => throwError(() => ({ status: 400 }))),
      });
      component.ngOnChanges();
      service.getTemplates.mockClear();

      component.onVersionRangeChange('1.x.y');

      expect(component.error).toBeNull();
      expect(component.designHints.release).toEqual([
        { key: 'versionRangeInvalid', problem: true },
      ]);
      expect(service.getTemplates).not.toHaveBeenCalled();
    });

    it('drops the range with the catalog it was written for, and both with the connection', () => {
      const { component } = createComponent({
        ...saved(),
        environmentId: '"acceptatie"',
        versionRange: '"^2.0.0"',
      });
      component.ngOnChanges();

      component.onCatalogSelected('local/valtimo/provincie');
      expect(component.value).toEqual({
        pluginConfigurationId: 'config-1',
        environmentId: '"acceptatie"',
        catalogId: 'local/valtimo/provincie',
        templates: [],
      });

      // The new connection's only catalog is chosen for the author, as ever; the environment and
      // the range belonged to the old one and are gone.
      component.onConfigurationSelected('config-2');
      expect(component.value?.pluginConfigurationId).toBe('config-2');
      expect(component.value).not.toHaveProperty('environmentId');
      expect(component.value).not.toHaveProperty('versionRange');
    });

    it('pins one letter to a release of its own, and drops the pin when it is cleared', () => {
      const { component } = createComponent(saved());

      component.setTemplateVersionRange('besluit', ' 1.4.0 ');
      expect(component.versionRangeOf('besluit')).toBe('"1.4.0"');
      expect(component.rangeText('besluit', component.versionRangeOf('besluit'))).toBe('1.4.0');
      expect(component.value?.templates[0]).toEqual({
        templateId: 'besluit',
        label: 'Besluit',
        versionRange: '"1.4.0"',
      });

      component.setTemplateVersionRange('besluit', '');
      expect(component.value?.templates[0]).toEqual({ templateId: 'besluit', label: 'Besluit' });
    });

    it("does not call a letter pinned to another release missing from the set's release", () => {
      const { component } = createComponent({
        ...saved(),
        templates: [
          { templateId: 'oud-besluit', versionRange: '1.0.0' },
          { templateId: 'verdwenen' },
        ],
      });
      component.ngOnChanges();

      expect(component.missingTemplates.map((template) => template.templateId)).toEqual([
        'verdwenen',
      ]);
    });
  });

  it('continues the cascade from what was already saved', () => {
    const { component, service } = createComponent({
      pluginConfigurationId: 'config-1',
      catalogId: 'gemeente',
      templates: [{ templateId: 'besluit', label: 'Besluit' }],
    });

    // Formio sets the saved value after construction, so the restore hangs off the input change.
    component.ngOnChanges();

    expect(service.getCatalogs).toHaveBeenCalledWith('config-1', undefined);
    expect(service.getTemplates).toHaveBeenCalledWith('config-1', 'gemeente', read());
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
