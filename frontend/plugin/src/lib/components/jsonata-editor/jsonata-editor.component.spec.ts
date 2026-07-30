/**
 * @jest-environment jsdom
 */

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

jest.mock('@angular/core', () => ({
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
  Output: () => () => undefined,
  EventEmitter: class {
    private handlers: Array<(value: unknown) => void> = [];
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
jest.mock('@valtimo/plugin', () => ({ PluginTranslatePipeModule: class {} }));
jest.mock('@valtimo/components', () => ({ EditorModule: class {} }));
jest.mock('../../utils/jsonata-monaco', () => ({
  jsonataCompletionData: { variables: {}, functions: [] },
  registerJsonataLanguage: jest.fn(),
}));

import { registerJsonataLanguage } from '../../utils/jsonata-monaco';
import { JsonataEditorComponent } from './jsonata-editor.component';

describe('JsonataEditorComponent', () => {
  beforeEach(() => {
    delete (window as any).monaco;
    jest.clearAllMocks();
  });

  it('does not replace the Monaco model when the parent echoes a local edit', () => {
    const component = new JsonataEditorComponent();
    component.expression = '{"name": $doc.name}';
    component.ngOnChanges({
      expression: {
        currentValue: component.expression,
        previousValue: '',
        firstChange: true,
        isFirstChange: () => true,
      },
    });
    const model = component.editorModel;
    const emitted: string[] = [];
    component.expressionChange.subscribe((value) => emitted.push(value));

    component.onEditorValueChange('{"name": $lowercase($doc.name)}');
    component.ngOnChanges({
      expression: {
        currentValue: emitted[0],
        previousValue: '{"name": $doc.name}',
        firstChange: false,
        isFirstChange: () => false,
      },
    });

    expect(emitted).toEqual(['{"name": $lowercase($doc.name)}']);
    expect(component.editorModel).toBe(model);
    expect(component.editorModel.value).toBe('{"name": $lowercase($doc.name)}');
    component.ngOnDestroy();
  });

  it('replaces the model only for a genuine external expression change', () => {
    const component = new JsonataEditorComponent();
    const model = component.editorModel;
    component.expression = '$doc.external';

    component.ngOnChanges({
      expression: {
        currentValue: component.expression,
        previousValue: '',
        firstChange: false,
        isFirstChange: () => false,
      },
    });

    expect(component.editorModel).not.toBe(model);
    expect(component.editorModel).toMatchObject({
      value: '$doc.external',
      language: 'jsonata',
    });
    expect(component.editorModel.uri).toBe(model.uri);
    component.ngOnDestroy();
  });

  it('registers JSONata after Monaco loads and retokenizes the initial model', () => {
    const animationFrames: FrameRequestCallback[] = [];
    const originalRequestAnimationFrame = globalThis.requestAnimationFrame;
    const originalCancelAnimationFrame = globalThis.cancelAnimationFrame;
    globalThis.requestAnimationFrame = jest.fn((callback: FrameRequestCallback) => {
      animationFrames.push(callback);
      return animationFrames.length;
    });
    globalThis.cancelAnimationFrame = jest.fn();
    const component = new JsonataEditorComponent();
    const model = { getLanguageId: () => 'jsonata' };
    const setModelLanguage = jest.fn();

    component.ngAfterViewInit();
    (window as any).monaco = {
      Uri: { parse: jest.fn((uri: string) => uri) },
      editor: {
        getModel: jest.fn(() => model),
        setModelLanguage,
      },
    };
    animationFrames[0](0);

    expect(registerJsonataLanguage).toHaveBeenCalledWith((window as any).monaco);
    expect(setModelLanguage).toHaveBeenNthCalledWith(1, model, 'plaintext');
    expect(setModelLanguage).toHaveBeenNthCalledWith(2, model, 'jsonata');

    component.ngOnDestroy();
    globalThis.requestAnimationFrame = originalRequestAnimationFrame;
    globalThis.cancelAnimationFrame = originalCancelAnimationFrame;
  });
});
