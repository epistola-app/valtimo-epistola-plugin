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

import {
  GenerateDocumentConfigurationComponent,
  resolveExpressionSelectPrefill,
} from './generate-document-configuration.component';

describe('resolveExpressionSelectPrefill', () => {
  const options = [
    { id: 'default', text: 'Default' },
    { id: 'production', text: 'Production' },
  ];

  it('uses the select only for an exact decoded option id', () => {
    expect(resolveExpressionSelectPrefill('"production"', options)).toEqual({
      expressionMode: false,
      expression: '',
      value: 'production',
    });
  });

  it('uses fx mode for dynamic expressions and unmatched literals', () => {
    expect(resolveExpressionSelectPrefill('$pv.environment', options)).toEqual({
      expressionMode: true,
      expression: '$pv.environment',
      value: '',
    });
    expect(resolveExpressionSelectPrefill('"staging"', options)).toEqual({
      expressionMode: true,
      expression: '"staging"',
      value: '',
    });
  });

  it('keeps an unconfigured field in select mode', () => {
    expect(resolveExpressionSelectPrefill(undefined, options)).toEqual({
      expressionMode: false,
      expression: '',
      value: '',
    });
  });
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
    expect(validity).toEqual([false]);
  });

  it('always emits v1 and preserves direct expression fields', () => {
    const { component, service } = createComponent();
    const save$ = new Subject<void>();
    component.save$ = save$;
    component.selectedCatalogId$.next('catalog');
    component.filenameExpression = '"letter.pdf"';
    component.environmentIdValue = 'production';
    component.variantIdValue = 'formal';
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

  it('applies exact-match selection only after environment and variant options load', () => {
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

    (component as any).loadedEnvironmentOptions$.next([{ id: 'production', text: 'Production' }]);
    (component as any).loadedVariantOptions$.next([{ id: 'default', text: 'Default' }]);

    expect(component.environmentIdExpressionMode).toBe(false);
    expect(component.environmentIdValue).toBe('production');
    expect(component.variantIdExpressionMode).toBe(true);
    expect(component.variantIdExpression).toBe('"missing-variant"');
  });
});
