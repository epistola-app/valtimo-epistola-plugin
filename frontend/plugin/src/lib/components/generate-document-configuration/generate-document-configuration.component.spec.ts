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

import { firstValueFrom, of, Subject } from 'rxjs';
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

import { GenerateDocumentConfigurationComponent } from './generate-document-configuration.component';

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
