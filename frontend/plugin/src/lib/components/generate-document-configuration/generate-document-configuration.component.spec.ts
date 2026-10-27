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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

import { firstValueFrom, of, Subject, throwError } from 'rxjs';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

jest.mock('@angular/core', () => ({
  ChangeDetectionStrategy: { OnPush: 'OnPush' },
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
  Output: () => () => undefined,
  EventEmitter: class {
    private handlers: ((value: unknown) => void)[] = [];
    emit(value: unknown) {
      this.handlers.forEach((handler) => handler(value));
    }
    subscribe(handler: (value: unknown) => void) {
      this.handlers.push(handler);
      return { unsubscribe: () => undefined };
    }
  },
}));
jest.mock('@angular/common', () => ({ CommonModule: class {} }));
jest.mock('@angular/forms', () => ({ FormsModule: class {} }));
jest.mock('@valtimo/plugin', () => ({ PluginTranslatePipeModule: class {} }));
jest.mock('@valtimo/components', () => ({
  FormModule: class {},
  InputModule: class {},
  SelectModule: class {},
}));
jest.mock('@valtimo/shared', () => ({}));
jest.mock('@valtimo/process-link', () => ({}));
jest.mock('../../services', () => ({}));
jest.mock('../jsonata-editor/jsonata-editor.component', () => ({
  JsonataEditorComponent: class {},
}));
jest.mock('../expected-structure/expected-structure.component', () => ({
  ExpectedStructureComponent: class {},
}));
jest.mock('../mapping-builder/mapping-builder.component', () => ({
  MappingBuilderComponent: class {},
}));
jest.mock('../mapping-preview/mapping-preview.component', () => ({
  MappingPreviewComponent: class {},
}));
jest.mock('../smart-expression-editor/smart-expression-editor.component', () => ({
  SmartExpressionEditorComponent: class {},
}));

import {
  environmentScopeOf,
  GenerateDocumentConfigurationComponent,
  releaseHintOf,
} from './generate-document-configuration.component';
import { ANY_ENVIRONMENT } from '../../services';

describe('GenerateDocumentConfigurationComponent selectors', () => {
  it.each(['catalogId', 'templateId'])(
    'renders the %s popup outside the process-link modal inline flow',
    (name) => {
      const template = readFileSync(
        join(
          process.cwd(),
          'src/lib/components/generate-document-configuration/generate-document-configuration.component.html',
        ),
        'utf8',
      );
      const matches = template.match(
        new RegExp(`<v-select\\s+name="${name}"[\\s\\S]*?</v-select>`),
      );
      if (!matches) {
        throw new Error(`Missing ${name} selector markup`);
      }
      const [selectMarkup] = matches;

      expect(selectMarkup).toContain('[appendInline]="false"');
      expect(selectMarkup).toContain('[dropUp]="false"');
    },
  );
});

describe('GenerateDocumentConfigurationComponent versioning', () => {
  const createComponent = () => {
    const service = {
      validateJsonata: jest.fn().mockReturnValue(of({ valid: true, errors: [] })),
    };
    const component = new GenerateDocumentConfigurationComponent(
      service as any,
      {} as any,
      { markForCheck: jest.fn(), detectChanges: jest.fn() } as any,
    );
    return { component, service };
  };

  it('migrates raw Valtimo prefill locally before exposing it to the form', async () => {
    const { component } = createComponent();
    component.prefillConfiguration$ = of({
      catalogId: 'catalog',
      templateId: 'template',
      dataMapping: '{}',
      outputFormat: 'PDF',
      filename: 'letter.pdf',
      resultProcessVariable: 'result',
    });

    const migrated = await firstValueFrom((component as any).resolvePrefill$());

    expect(migrated).toMatchObject({
      actionConfigVersion: 1,
      filename: '"letter.pdf"',
    });
    expect(component.configurationVersionError$.value).toBeNull();
    expect(component.legacyConfigurationLoaded$.value).toBe(true);
  });

  it('does not show the upgrade notice for an existing v1 prefill', async () => {
    const { component } = createComponent();
    component.prefillConfiguration$ = of({
      actionConfigVersion: 1,
      catalogId: 'catalog',
      templateId: 'template',
      dataMapping: '{}',
      outputFormat: '"PDF"',
      filename: '"letter.pdf"',
      resultProcessVariable: 'result',
    });

    await firstValueFrom((component as any).resolvePrefill$());

    expect(component.legacyConfigurationLoaded$.value).toBe(false);
  });

  it('opens an existing blank mapping as an editable empty object', async () => {
    const { component } = createComponent();
    component.prefillConfiguration$ = of({
      actionConfigVersion: 1,
      catalogId: 'catalog',
      templateId: 'template',
      dataMapping: '',
      outputFormat: '"PDF"',
      filename: '"letter.pdf"',
      resultProcessVariable: 'result',
    });

    const reopened = (await firstValueFrom((component as any).resolvePrefill$())) as {
      dataMapping: string;
    } | null;

    expect(reopened?.dataMapping).toBe('{}');
    expect(component.configurationVersionError$.value).toBeNull();
  });

  it('blocks an unsupported future configuration version', async () => {
    const { component } = createComponent();
    const validity: boolean[] = [];
    component.valid.subscribe((value) => validity.push(value));
    component.prefillConfiguration$ = of({
      actionConfigVersion: 2,
      catalogId: 'catalog',
      templateId: 'template',
      dataMapping: '{}',
      outputFormat: 'PDF',
      filename: '"letter.pdf"',
      resultProcessVariable: 'result',
    } as any);

    expect(await firstValueFrom((component as any).resolvePrefill$())).toBeNull();
    expect(component.configurationVersionError$.value).toContain('supports up to version 1');
    expect(component.legacyConfigurationLoaded$.value).toBe(false);
    expect(validity).toEqual([false]);
  });

  it('always emits v1 and preserves direct expression fields', () => {
    const { component, service } = createComponent();
    const save$ = new Subject<void>();
    component.save$ = save$;
    component.selectedCatalogId$.next('catalog');
    component.filenameExpression = '"letter.pdf"';
    component.environmentIdExpression = '"production"';
    component.variantIdExpression = '"formal"';
    component.correlationIdExpression = '"request-123"';
    component.dataMapping$.next('{}');
    (component as any).formValue$.next({
      templateId: 'template',
      outputFormat: 'PDF',
      resultProcessVariable: 'result',
    });
    (component as any).valid$.next(true);

    const emitted: unknown[] = [];
    component.configuration.subscribe((config) => emitted.push(config));
    (component as any).openSaveSubscription();
    save$.next();

    expect(service.validateJsonata).toHaveBeenCalledWith(
      expect.objectContaining({
        filename: '"letter.pdf"',
        outputFormat: '"PDF"',
        variantId: '"formal"',
        environmentId: '"production"',
        correlationId: '"request-123"',
      }),
    );
    expect(emitted).toEqual([
      expect.objectContaining({
        actionConfigVersion: 1,
        outputFormat: '"PDF"',
        filename: '"letter.pdf"',
        variantId: '"formal"',
        environmentId: '"production"',
        correlationId: '"request-123"',
      }),
    ]);
  });

  it('preserves environment and variant expressions while the option lists load independently', () => {
    const { component } = createComponent();
    (component as any).prefill$ = of({
      actionConfigVersion: 1,
      catalogId: 'catalog',
      templateId: 'template',
      dataMapping: '{}',
      outputFormat: '"PDF"',
      filename: '"letter.pdf"',
      environmentId: '"production"',
      variantId: '"missing-variant"',
      resultProcessVariable: 'result',
    });

    (component as any).initEnvironmentPrefill();
    (component as any).initVariantPrefill();

    expect(component.environmentIdExpression).toBe('"production"');
    expect(component.variantIdExpression).toBe('"missing-variant"');
  });

  it('includes inline expression validity in the action validity', () => {
    const { component } = createComponent();
    const validity: boolean[] = [];
    component.valid.subscribe((value) => validity.push(value));
    component.selectedCatalogId$.next('catalog');
    component.filenameExpression = '"letter.pdf"';
    component.dataMapping$.next('{}');
    (component as any).templateFieldsLoadedForTemplateId = 'template';
    (component as any).formValue$.next({
      templateId: 'template',
      outputFormat: 'PDF',
      resultProcessVariable: 'result',
    });

    component.onExpressionValidityChange('filename', false);
    component.onExpressionValidityChange('filename', true);

    expect(validity).toEqual([false, true]);
  });

  it('blocks save until required Simple mapping fields have values', () => {
    const { component, service } = createComponent();
    const save$ = new Subject<void>();
    component.save$ = save$;
    component.selectedCatalogId$.next('catalog');
    component.filenameExpression = '"letter.pdf"';
    component.templateFields$.next({
      data: [
        {
          name: 'name',
          path: 'name',
          type: 'string',
          fieldType: 'SCALAR',
          required: true,
        },
      ],
      loading: false,
      error: null,
    });
    (component as any).templateFieldsLoadedForTemplateId = 'template';
    const formValue = {
      templateId: 'template',
      resultProcessVariable: 'result',
    };
    (component as any).formValue$.next(formValue);
    (component as any).openSaveSubscription();

    component.dataMapping$.next('{}');
    (component as any).handleValid(formValue);
    save$.next();

    expect(service.validateJsonata).not.toHaveBeenCalled();
    expect(component.dataMappingCompleteness$.value).toMatchObject({
      mappedRequiredFields: 0,
      totalRequiredFields: 1,
      missingRequiredFields: ['name'],
    });

    component.onDataMappingChange('{"name": $doc.name}');
    save$.next();

    expect(service.validateJsonata).toHaveBeenCalledTimes(1);
  });

  it('keeps catalog and template selections in the reactive cascade', () => {
    const { component } = createComponent();
    const templateCleared = jest.fn();
    component.clearTemplateId$.subscribe(templateCleared);

    component.formValueChange({
      catalogId: 'catalog',
    } as Parameters<typeof component.formValueChange>[0]);

    expect(component.selectedCatalogId$.value).toBe('catalog');
    expect(component.selectedTemplateId$.value).toBe('');
    expect(templateCleared).toHaveBeenCalledTimes(1);

    component.formValueChange({
      catalogId: 'catalog',
      templateId: 'template',
    } as Parameters<typeof component.formValueChange>[0]);

    expect(component.selectedCatalogId$.value).toBe('catalog');
    expect(component.selectedTemplateId$.value).toBe('template');
  });

  it('does not switch an unsupported whole mapping to simple mode', () => {
    const { component } = createComponent();
    component.mappingMode = 'advanced';
    component.dataMapping$.next('$merge([{"name": $doc.name}, $pv.extra])');

    expect(component.canUseSimpleMapping()).toBe(false);
    component.onMappingModeChange('simple');
    expect(component.mappingMode).toBe('advanced');

    component.dataMapping$.next('{"name": $uppercase($doc.name)}');
    expect(component.canUseSimpleMapping()).toBe(true);
    component.onMappingModeChange('simple');
    expect(component.mappingMode).toBe('simple');
  });

  it('forces Advanced mode only when the schema root is unsupported', () => {
    const { component } = createComponent();
    component.dataMapping$.next('{}');

    (component as any).applyTemplateSchemaDetails({
      id: 'template',
      name: 'Template',
      fields: [],
      schema: { oneOf: [{ type: 'object' }, { type: 'array' }] },
      simpleMappingSupport: { level: 'UNSUPPORTED', reason: 'Root union' },
    });

    expect(component.mappingMode).toBe('advanced');
    expect(component.canUseSimpleMapping()).toBe(false);

    (component as any).applyTemplateSchemaDetails({
      id: 'template-2',
      name: 'Template 2',
      fields: [],
      schema: { type: 'object', properties: {} },
      simpleMappingSupport: { level: 'FULL' },
    });

    expect(component.mappingMode).toBe('simple');
    expect(component.canUseSimpleMapping()).toBe(true);
  });

  it('keeps Simple mode for partially supported schemas', () => {
    const { component } = createComponent();

    (component as any).applyTemplateSchemaDetails({
      id: 'template',
      name: 'Template',
      fields: [],
      schema: { type: 'object', properties: { subject: { oneOf: [] } } },
      simpleMappingSupport: { level: 'PARTIAL', reason: 'Complex field' },
    });

    expect(component.mappingMode).toBe('simple');
    expect(component.simpleMappingSupport$.value.level).toBe('PARTIAL');
    expect(component.templateSchema$.value).toMatchObject({ type: 'object' });
  });
});

describe('GenerateDocumentConfigurationComponent catalog and template changes', () => {
  type FormChange = Parameters<GenerateDocumentConfigurationComponent['formValueChange']>[0];
  const formChange = (component: GenerateDocumentConfigurationComponent, value: unknown) =>
    component.formValueChange(value as FormChange);

  const TEMPLATES: Record<string, string[]> = {
    'catalog-a': ['invoice', 'reminder'],
    'catalog-b': ['letter'],
  };

  const createCascade = (
    prefill: unknown = null,
    catalogs: unknown[] = [
      { id: 'catalog-a', name: 'Catalog A' },
      { id: 'catalog-b', name: 'Catalog B' },
    ],
  ) => {
    const service = {
      getCatalogs: jest.fn().mockReturnValue(of(catalogs)),
      getEnvironments: jest.fn().mockReturnValue(of([])),
      getTenantId: jest.fn().mockReturnValue(of('demo')),
      getTemplates: jest.fn((_configurationId: string, catalogId: string) =>
        of((TEMPLATES[catalogId] ?? []).map((id) => ({ id, name: id }))),
      ),
      getAttributes: jest.fn().mockReturnValue(of([])),
      getVariants: jest.fn((_configurationId: string, templateId: string) =>
        of([{ id: 'default', templateId, name: 'Default', attributes: {} }]),
      ),
      getTemplateDetails: jest.fn((_configurationId: string, templateId: string) =>
        of({
          id: templateId,
          name: templateId,
          fields: [{ name: 'name', path: 'name', type: 'string', fieldType: 'SCALAR' }],
          schema: { type: 'object', properties: { name: { type: 'string' } } },
          simpleMappingSupport: { level: 'FULL' },
        }),
      ),
      getReleases: jest.fn((_c: string, _catalog: string, _env: unknown, range?: string) =>
        of({ versions: ['2.0.0', '1.9.0'], selected: range ? '1.9.0' : '2.0.0', draft: false }),
      ),
      validateJsonata: jest.fn().mockReturnValue(of({ valid: true, errors: [] })),
    };
    const component = new GenerateDocumentConfigurationComponent(
      service as any,
      {} as any,
      { markForCheck: jest.fn(), detectChanges: jest.fn() } as any,
    );
    (component as any).prefill$ = of(prefill);
    (component as any).initEnvironmentPrefill();
    (component as any).initCascade();
    (component as any).initVariantPrefill();
    (component as any).pluginConfigurationId$.next('config');
    return { component, service };
  };

  const selectInvoiceInCatalogA = (component: GenerateDocumentConfigurationComponent) => {
    formChange(component, { catalogId: 'catalog-a' });
    formChange(component, { catalogId: 'catalog-a', templateId: 'invoice' });
  };

  it('does not ask the new catalog for the template selected in the previous one', () => {
    const { component, service } = createCascade();
    selectInvoiceInCatalogA(component);
    expect(service.getTemplateDetails).toHaveBeenCalledWith(
      'config',
      'invoice',
      'catalog-a',
      undefined,
      '',
    );
    service.getTemplateDetails.mockClear();
    service.getVariants.mockClear();

    // The form still carries the old template id in the emission that changes the catalog.
    formChange(component, { catalogId: 'catalog-b', templateId: 'invoice' });

    expect(service.getTemplates).toHaveBeenLastCalledWith('config', 'catalog-b', undefined, '');
    expect(service.getTemplateDetails).not.toHaveBeenCalled();
    expect(service.getVariants).not.toHaveBeenCalled();
    expect(component.selectedTemplateId$.value).toBe('');
    expect(component.templates$.value.data.map((item) => item.id)).toEqual(['letter']);
  });

  it('clears the template without requesting an empty template id', () => {
    const { component, service } = createCascade();
    selectInvoiceInCatalogA(component);
    service.getTemplateDetails.mockClear();
    service.getVariants.mockClear();

    // Carbon's combo box reports a cleared single selection as [] before v-select emits ''.
    formChange(component, { catalogId: 'catalog-a', templateId: [] });
    formChange(component, { catalogId: 'catalog-a', templateId: '' });

    expect(service.getTemplateDetails).not.toHaveBeenCalled();
    expect(service.getVariants).not.toHaveBeenCalled();
    expect(component.selectedTemplateId$.value).toBe('');
    expect(component.variants$.value.data).toEqual([]);
    expect(component.templateFields$.value.data).toEqual([]);
    expect(component.templateSchema$.value).toBeNull();
  });

  it('resets the variant selection and data mapping when the template changes', () => {
    const { component, service } = createCascade();
    selectInvoiceInCatalogA(component);
    component.variantIdExpression = '"formal"';
    component.onVariantSelectionModeChange('attributes');
    component.dataMapping$.next('{"name": $doc.name}');
    component.mappingMode = 'advanced';
    component.filenameExpression = '"letter.pdf"';
    component.environmentIdExpression = '"production"';
    component.correlationIdExpression = '"request-123"';

    formChange(component, { catalogId: 'catalog-a', templateId: 'reminder' });

    expect(service.getTemplateDetails).toHaveBeenLastCalledWith(
      'config',
      'reminder',
      'catalog-a',
      undefined,
      '',
    );
    expect(component.variantIdExpression).toBe('');
    expect(component.variantSelectionMode).toBe('explicit');
    expect(component.variantAttributeEntries).toEqual([]);
    expect(component.dataMapping$.value).toBe('{}');
    expect(component.mappingMode).toBe('simple');
    // Not template-specific, so they stay.
    expect(component.filenameExpression).toBe('"letter.pdf"');
    expect(component.environmentIdExpression).toBe('"production"');
    expect(component.correlationIdExpression).toBe('"request-123"');
  });

  it('resets the template and everything under it when the catalog changes', () => {
    const { component } = createCascade();
    selectInvoiceInCatalogA(component);
    component.variantIdExpression = '"formal"';
    component.dataMapping$.next('{"name": $doc.name}');

    formChange(component, { catalogId: 'catalog-b', templateId: 'invoice' });

    expect(component.selectedCatalogId$.value).toBe('catalog-b');
    expect(component.selectedTemplateId$.value).toBe('');
    expect(component.variantIdExpression).toBe('');
    expect(component.dataMapping$.value).toBe('{}');
    expect(component.variants$.value.data).toEqual([]);
    expect(component.templateFields$.value.data).toEqual([]);
  });

  it('empties the template list when the catalog is cleared', () => {
    const { component, service } = createCascade();
    selectInvoiceInCatalogA(component);
    service.getTemplates.mockClear();

    formChange(component, { catalogId: '', templateId: 'invoice' });

    expect(service.getTemplates).not.toHaveBeenCalled();
    expect(component.selectedCatalogId$.value).toBe('');
    expect(component.selectedTemplateId$.value).toBe('');
    expect(component.templates$.value.data).toEqual([]);
  });

  it('labels each catalog with where it lives, the tenant’s own first', () => {
    const { component, service } = createCascade();
    service.getCatalogs.mockReturnValue(
      of([
        {
          id: 'exchange.epistola.app/vng/brieven',
          name: 'Brieven',
          registry: 'exchange.epistola.app',
          namespace: 'vng',
          key: 'brieven',
        },
        {
          id: 'local/valtimo/brieven',
          name: 'Brieven',
          registry: 'local',
          namespace: 'valtimo',
          key: 'brieven',
        },
      ]),
    );
    (component as any).pluginConfigurationId$.next('other-config');

    expect(component.catalogs$.value.data).toEqual([
      { id: 'local/valtimo/brieven', text: 'Brieven (valtimo)' },
      { id: 'exchange.epistola.app/vng/brieven', text: 'Brieven (vng · exchange.epistola.app)' },
    ]);
  });

  it("selects the tenant's own catalog for a 1.x key several namespaces share", () => {
    // Epistola's 2.0 upgrade named the catalogs tenant "demo" had local/demo/<key>; generation
    // resolves the stored key to that one, so the configurator shows it chosen.
    const brieven = (registry: string, namespace: string) => ({
      id: `${registry}/${namespace}/brieven`,
      name: 'Brieven',
      registry,
      namespace,
      key: 'brieven',
    });
    const { component, service } = createCascade({ version: 1, catalogId: 'brieven' }, [
      brieven('exchange.epistola.app', 'vng'),
      brieven('local', 'demo'),
    ]);

    expect(service.getTenantId).toHaveBeenCalledWith('config');
    expect(component.selectedCatalogId$.value).toBe('local/demo/brieven');
  });

  it('narrows the lookups to the environment the action names', () => {
    jest.useFakeTimers();
    try {
      const { component, service } = createCascade();
      selectInvoiceInCatalogA(component);

      component.onEnvironmentIdExpressionChange('"productie"');
      jest.advanceTimersByTime(250);

      expect(service.getCatalogs).toHaveBeenLastCalledWith('config', 'productie');
      expect(service.getTemplates).toHaveBeenLastCalledWith('config', 'catalog-a', 'productie', '');
      expect(service.getTemplateDetails).toHaveBeenLastCalledWith(
        'config',
        'invoice',
        'catalog-a',
        'productie',
        '',
      );
    } finally {
      jest.useRealTimers();
    }
  });

  it('keeps a catalog the environment does not serve selected, and says so', () => {
    jest.useFakeTimers();
    try {
      const { component, service } = createCascade();
      selectInvoiceInCatalogA(component);
      const notices: boolean[] = [];
      component.catalogNotOffered$.subscribe((notOffered) => notices.push(notOffered));
      const items: string[][] = [];
      component.catalogItems$.subscribe((resource) =>
        items.push(resource.data.map((i) => String(i.id))),
      );

      service.getCatalogs.mockReturnValue(of([{ id: 'catalog-b', name: 'Catalog B' }]));
      component.onEnvironmentIdExpressionChange('"productie"');
      jest.advanceTimersByTime(250);

      expect(component.selectedCatalogId$.value).toBe('catalog-a');
      expect(component.selectedTemplateId$.value).toBe('invoice');
      expect(items[items.length - 1]).toEqual(['catalog-b', 'catalog-a']);
      expect(notices[notices.length - 1]).toBe(true);
    } finally {
      jest.useRealTimers();
    }
  });

  it('reads the template from the release the version range selects, keeping what was chosen', () => {
    jest.useFakeTimers();
    try {
      const { component, service } = createCascade();
      selectInvoiceInCatalogA(component);
      component.dataMapping$.next('{"name": $doc.name}');

      component.onVersionRangeChange(' ^1.4.0 ');
      jest.advanceTimersByTime(250);

      expect(service.getReleases).toHaveBeenLastCalledWith(
        'config',
        'catalog-a',
        undefined,
        '^1.4.0',
      );
      expect(service.getTemplates).toHaveBeenLastCalledWith(
        'config',
        'catalog-a',
        undefined,
        '^1.4.0',
      );
      expect(service.getTemplateDetails).toHaveBeenLastCalledWith(
        'config',
        'invoice',
        'catalog-a',
        undefined,
        '^1.4.0',
      );
      // Another release of the same catalog: the template and its mapping stay.
      expect(component.selectedTemplateId$.value).toBe('invoice');
      expect(component.dataMapping$.value).toBe('{"name": $doc.name}');
    } finally {
      jest.useRealTimers();
    }
  });

  it('keeps a template the selected release does not have, and says so', () => {
    jest.useFakeTimers();
    try {
      const { component, service } = createCascade();
      selectInvoiceInCatalogA(component);
      const notices: boolean[] = [];
      component.templateNotOffered$.subscribe((notOffered) => notices.push(notOffered));
      const items: string[][] = [];
      component.templateItems$.subscribe((resource) =>
        items.push(resource.data.map((i) => String(i.id))),
      );

      service.getTemplates.mockImplementation(() => of([{ id: 'reminder', name: 'Reminder' }]));
      component.onVersionRangeChange('1.0.0');
      jest.advanceTimersByTime(250);

      expect(component.selectedTemplateId$.value).toBe('invoice');
      // Kept under the label it was listed with.
      expect(items[items.length - 1]).toEqual(['reminder', 'invoice']);
      expect(notices[notices.length - 1]).toBe(true);
    } finally {
      jest.useRealTimers();
    }
  });

  it('refuses to save a range that is not one, and says why', async () => {
    jest.useFakeTimers();
    try {
      const { component, service } = createCascade();
      selectInvoiceInCatalogA(component);
      const validity: boolean[] = [];
      component.valid.subscribe((valid) => validity.push(valid));
      service.getReleases.mockReturnValue(throwError(() => ({ status: 400 })));

      component.onVersionRangeChange('latest');
      jest.advanceTimersByTime(250);

      expect(validity[validity.length - 1]).toBe(false);
      expect(await firstValueFrom(component.releaseHint$)).toEqual({
        key: 'versionRangeInvalid',
        problem: true,
      });
    } finally {
      jest.useRealTimers();
    }
  });

  it('seeds the version range from a saved configuration', () => {
    const { component, service } = createCascade({
      actionConfigVersion: 1,
      catalogId: 'catalog-a',
      templateId: 'invoice',
      versionRange: '1.9.0',
      dataMapping: '{}',
      outputFormat: '"PDF"',
      filename: '"letter.pdf"',
      resultProcessVariable: 'result',
    });

    expect(component.versionRange).toBe('1.9.0');
    formChange(component, { catalogId: 'catalog-a' });
    formChange(component, { catalogId: 'catalog-a', templateId: 'invoice' });
    // Read once, from the saved release: never first from the highest one.
    expect(service.getTemplateDetails).toHaveBeenCalledTimes(1);
    expect(service.getTemplateDetails).toHaveBeenCalledWith(
      'config',
      'invoice',
      'catalog-a',
      undefined,
      '1.9.0',
    );
  });

  it('keeps a saved configuration intact while it loads', () => {
    const { component, service } = createCascade({
      actionConfigVersion: 1,
      catalogId: 'catalog-a',
      templateId: 'invoice',
      variantId: '"formal"',
      dataMapping: '{"name": $doc.name}',
      outputFormat: '"PDF"',
      filename: '"letter.pdf"',
      resultProcessVariable: 'result',
    });

    // The selects apply their saved defaults, and the form reports them.
    formChange(component, { catalogId: 'catalog-a' });
    formChange(component, { catalogId: 'catalog-a', templateId: 'invoice' });

    expect(component.selectedCatalogId$.value).toBe('catalog-a');
    expect(component.selectedTemplateId$.value).toBe('invoice');
    expect(component.variantIdExpression).toBe('"formal"');
    expect(component.dataMapping$.value).toBe('{"name": $doc.name}');
    expect(service.getTemplateDetails).toHaveBeenCalledTimes(1);
    expect(service.getTemplateDetails).toHaveBeenCalledWith(
      'config',
      'invoice',
      'catalog-a',
      undefined,
      '',
    );
  });
});

describe('environmentScopeOf', () => {
  it('defers to the plugin default when no environment is set', () => {
    expect(environmentScopeOf('')).toBeUndefined();
    expect(environmentScopeOf(undefined)).toBeUndefined();
  });

  it('reads the environment a string literal names', () => {
    expect(environmentScopeOf('"productie"')).toBe('productie');
    expect(environmentScopeOf("'acceptatie'")).toBe('acceptatie');
  });

  it('narrows nothing for an environment only known at run time', () => {
    expect(environmentScopeOf('$pv.environment')).toBe(ANY_ENVIRONMENT);
  });
});

describe('releaseHintOf', () => {
  const state = (range: string, data: object | null, error: string | null = null) => ({
    range,
    resource: { data: data as any, loading: false, error },
  });

  it('names the release a range selects', () => {
    expect(
      releaseHintOf(state('^1.0.0', { versions: ['1.9.0'], selected: '1.9.0', draft: false })),
    ).toEqual({
      key: 'versionRangeSelects',
      version: '1.9.0',
      problem: false,
    });
  });

  it('warns when no release satisfies the range', () => {
    expect(
      releaseHintOf(state('^3.0.0', { versions: ['1.9.0'], selected: null, draft: false })),
    ).toEqual({
      key: 'versionRangeMatchesNothing',
      problem: true,
    });
  });

  it('without a range, says the environment renders its draft, else the highest release', () => {
    expect(
      releaseHintOf(state('', { versions: ['1.9.0'], selected: null, draft: true }))?.key,
    ).toBe('versionRangeNoneDraft');
    expect(
      releaseHintOf(state('', { versions: ['1.9.0'], selected: '1.9.0', draft: false })),
    ).toEqual({
      key: 'versionRangeNoneHighest',
      version: '1.9.0',
      problem: false,
    });
  });

  it('says nothing while loading, or when the lookup failed for another reason', () => {
    expect(
      releaseHintOf({ range: '1.0.0', resource: { data: null, loading: true, error: null } }),
    ).toBeNull();
    expect(releaseHintOf(state('1.0.0', null, 'Failed to load releases'))).toBeNull();
  });
});
