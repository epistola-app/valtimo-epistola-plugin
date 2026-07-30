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
  ChangeDetectionStrategy: { OnPush: 'OnPush' },
  Component: () => (target: unknown) => target,
  Input: () => () => undefined,
  Output: () => () => undefined,
  ViewChild: () => () => undefined,
  HostListener: () => () => undefined,
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
jest.mock('@angular/forms', () => ({ FormsModule: class {} }));
jest.mock('@valtimo/plugin', () => ({ PluginTranslatePipeModule: class {} }));

import { SmartExpressionEditorComponent } from './smart-expression-editor.component';

describe('SmartExpressionEditorComponent', () => {
  beforeAll(() => {
    if (!Range.prototype.getBoundingClientRect) {
      Range.prototype.getBoundingClientRect = () =>
        ({
          left: 0,
          right: 0,
          top: 0,
          bottom: 0,
          width: 0,
          height: 0,
          x: 0,
          y: 0,
          toJSON: () => ({}),
        }) as DOMRect;
    }
  });

  const createComponent = (expression = '') => {
    const component = new SmartExpressionEditorComponent(
      { markForCheck: jest.fn() } as any,
      { runOutsideAngular: (callback: () => void) => callback() } as any,
    );
    component.contextVariables = {
      doc: ['name', 'address.street'],
      pv: ['filename'],
      case: [],
    };
    component.expression = expression;
    component.ngOnChanges({
      expression: {
        currentValue: expression,
        previousValue: undefined,
        firstChange: true,
        isFirstChange: () => true,
      },
    });

    const surface = document.createElement('div');
    surface.contentEditable = 'true';
    document.body.append(surface);
    (component as any).surface = { nativeElement: surface };
    component.ngAfterViewInit();

    return {
      component,
      surface,
      destroy: () => {
        component.ngOnDestroy();
        surface.remove();
      },
    };
  };

  const setCaret = (node: Node, offset: number) => {
    const selection = window.getSelection()!;
    const range = document.createRange();
    range.setStart(node, offset);
    range.collapse(true);
    selection.removeAllRanges();
    selection.addRange(range);
  };

  it('shows persisted references as accessible chips', () => {
    const { component, surface, destroy } = createComponent('$pv.filename');

    const chip = surface.querySelector<HTMLElement>('[data-expression-chip]');
    expect(component.mode).toBe('simple');
    expect(chip?.textContent).toBe('$pv.filename');
    expect(chip?.getAttribute('role')).toBe('button');
    expect(chip?.getAttribute('aria-label')).toContain('Delete to remove');

    destroy();
  });

  it('always treats ordinary typing as literal text', () => {
    const { component, surface, destroy } = createComponent('');
    const expressions: string[] = [];
    component.expressionChange.subscribe((value) => expressions.push(value as string));
    surface.textContent = '$pv.filename';
    setCaret(surface.firstChild!, '$pv.filename'.length);

    component.onSurfaceInput();

    expect(expressions.at(-1)).toBe("'$pv.filename'");
    destroy();
  });

  it('replaces an @ query with an explicitly selected reference', () => {
    const { component, surface, destroy } = createComponent('');
    const expressions: string[] = [];
    component.expressionChange.subscribe((value) => expressions.push(value as string));
    surface.textContent = '@name';
    setCaret(surface.firstChild!, 5);

    component.onSurfaceInput();
    expect(component.pickerOpen).toBe(true);
    expect(component.pickerQuery).toBe('name');

    component.selectReference(component.flatReferenceOptions[0]);

    expect(expressions.at(-1)).toBe('$doc.name');
    expect(surface.querySelector('[data-expression-chip]')?.textContent).toBe('$doc.name');
    destroy();
  });

  it('keeps a dismissed @ query as literal text', () => {
    const { component, surface, destroy } = createComponent('');
    const expressions: string[] = [];
    component.expressionChange.subscribe((value) => expressions.push(value as string));
    surface.textContent = '@missing';
    setCaret(surface.firstChild!, 8);
    component.onSurfaceInput();

    component.onSurfaceKeydown(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(component.pickerOpen).toBe(false);
    expect(expressions.at(-1)).toBe("'@missing'");
    destroy();
  });

  it('inserts references and typed values at the saved cursor', () => {
    const { component, surface, destroy } = createComponent("'prefix-'");
    const expressions: string[] = [];
    component.expressionChange.subscribe((value) => expressions.push(value as string));
    setCaret(surface.firstChild!, 'prefix-'.length);
    component.onSurfaceFocus();

    component.openInsertPicker();
    component.selectReference(
      component.flatReferenceOptions.find((option) => option.expression === '$doc.name')!,
    );

    expect(expressions.at(-1)).toBe("'prefix-' & $doc.name");

    component.openInsertPicker();
    component.insertBoolean(true);
    expect(expressions.at(-1)).toBe("'prefix-' & $doc.name & true");
    destroy();
  });

  it('opens the insert picker on mouse down without losing the saved cursor', () => {
    const { component, surface, destroy } = createComponent(`'prefix-'`);
    setCaret(surface.firstChild!, 'prefix-'.length);
    component.onSurfaceFocus();
    const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    const stopPropagation = jest.spyOn(event, 'stopPropagation');

    component.onInsertMouseDown(event);

    expect(event.defaultPrevented).toBe(true);
    expect(stopPropagation).toHaveBeenCalled();
    expect(component.pickerOpen).toBe(true);
    component.onReferenceMouseDown(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
      component.flatReferenceOptions.find((option) => option.expression === '$doc.name')!,
    );
    expect(component.expression).toBe(`'prefix-' & $doc.name`);
    expect(component.pickerOpen).toBe(false);
    destroy();
  });

  it('replaces a clicked reference chip on mouse down and closes the picker', () => {
    const { component, surface, destroy } = createComponent('$pv.filename');
    component.onSurfaceClick({
      target: surface.querySelector('[data-expression-chip]'),
      preventDefault: jest.fn(),
    } as unknown as MouseEvent);
    expect(component.pickerOpen).toBe(true);

    component.onReferenceMouseDown(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
      component.flatReferenceOptions.find((option) => option.expression === '$doc.name')!,
    );

    expect(component.expression).toBe('$doc.name');
    expect(component.pickerOpen).toBe(false);
    expect(surface.querySelector('[data-expression-chip]')?.textContent).toBe('$doc.name');
    destroy();
  });

  it('opens unsupported persisted JSONata in validated Advanced mode', () => {
    jest.useFakeTimers();
    const { component, destroy } = createComponent('$uppercase($doc.name)');
    expect(component.mode).toBe('advanced');

    jest.advanceTimersByTime(300);
    expect(component.rawError).toBeNull();
    expect(component.rawRepresentable).toBe(false);

    const textarea = document.createElement('textarea');
    component.onRawInput('$doc.[broken', textarea);
    jest.advanceTimersByTime(300);
    expect(component.rawError).toBeTruthy();
    expect(component.advancedValid).toBe(false);

    destroy();
    jest.useRealTimers();
  });

  it('allows Advanced to Simple only for a losslessly representable expression', () => {
    jest.useFakeTimers();
    const { component, surface, destroy } = createComponent('$uppercase($doc.name)');
    component.rawExpression = `"letter-" & $doc.name`;
    component.onRawBlur();

    expect(component.rawRepresentable).toBe(true);
    component.switchToSimple();
    jest.runAllTicks();

    expect(component.mode).toBe('simple');
    expect(surface.textContent).toContain('letter-');
    expect(surface.querySelector('[data-expression-chip]')?.textContent).toBe('$doc.name');

    destroy();
    jest.useRealTimers();
  });

  it('renders the expression when Angular reattaches the Visual surface', () => {
    const { component, surface, destroy } = createComponent(`"letter-" & $doc.name`);
    component.switchToAdvanced();
    (component as any).surface = undefined;
    surface.remove();

    component.switchToSimple();
    const reattachedSurface = document.createElement('div');
    reattachedSurface.contentEditable = 'true';
    document.body.append(reattachedSurface);
    (component as any).surfaceView = { nativeElement: reattachedSurface };

    expect(component.mode).toBe('simple');
    expect(reattachedSurface.textContent).toContain('letter-');
    expect(reattachedSurface.querySelector('[data-expression-chip]')?.textContent).toBe(
      '$doc.name',
    );
    reattachedSurface.remove();
    destroy();
  });

  it('shows an untouched expression source exactly when opening Advanced mode', () => {
    const { component, destroy } = createComponent(`"prefix-"&$doc.name`);

    component.switchToAdvanced();

    expect(component.rawExpression).toBe(`"prefix-"&$doc.name`);
    destroy();
  });
});
