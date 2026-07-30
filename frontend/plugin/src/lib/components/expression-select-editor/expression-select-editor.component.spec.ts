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
  ViewChild: () => () => undefined,
  EventEmitter: class {
    emit = jest.fn();
  },
}));
jest.mock('@angular/common', () => ({ CommonModule: class {} }));
jest.mock('@angular/forms', () => ({ FormsModule: class {} }));
jest.mock('@valtimo/components', () => ({
  SelectModule: class {},
}));
jest.mock('@valtimo/plugin', () => ({ PluginTranslatePipeModule: class {} }));

import { ExpressionSelectEditorComponent } from './expression-select-editor.component';

describe('ExpressionSelectEditorComponent', () => {
  const changes = (expression: string) =>
    ({
      expression: {
        currentValue: expression,
        previousValue: undefined,
        firstChange: true,
        isFirstChange: () => true,
      },
    }) as any;

  const createComponent = (expression: string) => {
    const cdr = { markForCheck: jest.fn() };
    const component = new ExpressionSelectEditorComponent(cdr as any);
    component.items = [
      { id: 'default', text: 'Default' },
      { id: 'production', text: 'Production' },
    ];
    component.expression = expression;
    component.ngOnChanges(changes(expression));
    return component;
  };

  it('shows an exact string literal as a selected option', () => {
    const component = createComponent('"production"');

    expect(component.mode).toBe('select');
    expect(component.selectedValue).toBe('production');
  });

  it('shows a dynamic or unmatched expression in the advanced editor', () => {
    expect(createComponent('$pv.environment').mode).toBe('advanced');
    expect(createComponent('"staging"').mode).toBe('advanced');
  });

  it('stores a selected option as a JSONata string literal', () => {
    const component = createComponent('');

    component.onSelectedValueChange('production');

    expect(component.rawExpression).toBe('"production"');
    expect(component.expressionChange.emit).toHaveBeenCalledWith('"production"');
  });

  it('only allows returning to the select for an exact option', () => {
    const component = createComponent('$pv.environment');

    component.toggleMode();
    expect(component.mode).toBe('advanced');

    component.onRawInput('"default"', { style: {}, scrollHeight: 40 } as HTMLTextAreaElement);
    component.toggleMode();

    expect(component.mode).toBe('select');
    expect(component.selectedValue).toBe('default');
  });

  it('allows an empty optional expression to return to the unselected dropdown', () => {
    const component = createComponent('');

    component.toggleMode();
    expect(component.mode).toBe('advanced');

    component.toggleMode();
    expect(component.mode).toBe('select');
    expect(component.selectedValue).toBe('');
  });
});
