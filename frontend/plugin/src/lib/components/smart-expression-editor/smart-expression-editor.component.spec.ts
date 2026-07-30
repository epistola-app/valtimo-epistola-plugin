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

    const root = document.createElement('div');
    root.className = 'smart-expression';
    const surfaceWrap = document.createElement('div');
    surfaceWrap.className = 'smart-expression__surface-wrap';
    const surface = document.createElement('div');
    surface.contentEditable = 'true';
    surfaceWrap.append(surface);
    root.append(surfaceWrap);
    document.body.append(root);
    (component as any).surface = { nativeElement: surface };
    component.ngAfterViewInit();

    return {
      component,
      root,
      surface,
      destroy: () => {
        component.ngOnDestroy();
        root.remove();
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

  it('offers an unlisted name as a process variable', () => {
    const { component, surface, destroy } = createComponent('');
    surface.textContent = '@paymentReference';
    setCaret(surface.firstChild!, '@paymentReference'.length);

    component.onSurfaceInput();

    expect(component.flatReferenceOptions).toHaveLength(0);
    expect(component.customReferenceOption?.expression).toBe('$pv.paymentReference');
    component.selectReference(component.customReferenceOption!);

    expect(component.expression).toBe('$pv.paymentReference');
    expect(surface.querySelector('[data-expression-chip]')?.textContent).toBe(
      '$pv.paymentReference',
    );
    destroy();
  });

  it('accepts an explicit scope for an unlisted variable', () => {
    const { component, surface, destroy } = createComponent('');
    surface.textContent = '@case.owner.name';
    setCaret(surface.firstChild!, '@case.owner.name'.length);

    component.onSurfaceInput();

    expect(component.customReferenceOption?.expression).toBe('$case.owner.name');
    component.selectReference(component.customReferenceOption!);

    expect(component.expression).toBe('$case.owner.name');
    destroy();
  });

  it('does not duplicate an exact known variable as an unlisted option', () => {
    const { component, surface, destroy } = createComponent('');
    surface.textContent = '@pv.filename';
    setCaret(surface.firstChild!, '@pv.filename'.length);

    component.onSurfaceInput();

    expect(component.flatReferenceOptions.map((option) => option.expression)).toContain(
      '$pv.filename',
    );
    expect(component.customReferenceOption).toBeNull();
    destroy();
  });

  it('treats a typed + as text instead of duplicating the insert button shortcut', () => {
    const { component, surface, destroy } = createComponent(`'beforeafter'`);
    const expressions: string[] = [];
    component.expressionChange.subscribe((value) => expressions.push(value));
    surface.textContent = 'before+after';
    setCaret(surface.firstChild!, 'before+'.length);

    component.onSurfaceInput();

    expect(component.pickerOpen).toBe(false);
    expect(expressions.at(-1)).toBe(`'before+after'`);
    destroy();
  });

  it('replaces an @ query in the middle of literal text at its cursor position', () => {
    const { component, surface, destroy } = createComponent(`'beforeafter'`);
    surface.textContent = 'before@nameafter';
    setCaret(surface.firstChild!, 'before@name'.length);

    component.onSurfaceInput();
    component.selectReference(
      component.flatReferenceOptions.find((option) => option.expression === '$doc.name')!,
    );

    expect(component.expression).toBe(`'before' & $doc.name & 'after'`);
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

  it('keeps typing literal after Escape dismisses an @ search', () => {
    const { component, surface, destroy } = createComponent('');
    const expressions: string[] = [];
    component.expressionChange.subscribe((value) => expressions.push(value));
    surface.textContent = '@';
    setCaret(surface.firstChild!, 1);
    component.onSurfaceInput();

    component.onPickerSearchKeydown(
      new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }),
    );
    const textNode = surface.firstChild!;
    textNode.textContent = '@example.com';
    setCaret(textNode, '@example.com'.length);
    component.onSurfaceInput();

    expect(component.pickerOpen).toBe(false);
    expect(expressions.at(-1)).toBe("'@example.com'");
    destroy();
  });

  it('offers an explicit action that keeps @ as literal text', () => {
    const { component, surface, destroy } = createComponent('');
    surface.textContent = '@';
    setCaret(surface.firstChild!, 1);
    component.onSurfaceInput();
    const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true });

    component.onLiteralAtMouseDown(event);

    expect(event.defaultPrevented).toBe(true);
    expect(component.pickerOpen).toBe(false);
    expect(component.expression).toBe("'@'");
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

  it('restores the caret after the picker closing focus cycle', () => {
    const animationFrames: FrameRequestCallback[] = [];
    const originalRequestAnimationFrame = globalThis.requestAnimationFrame;
    globalThis.requestAnimationFrame = jest.fn((callback: FrameRequestCallback) => {
      animationFrames.push(callback);
      return animationFrames.length;
    });
    const { component, surface, destroy } = createComponent("'prefix-suffix'");
    setCaret(surface.firstChild!, 'prefix-'.length);
    component.onSurfaceFocus();

    component.openInsertPicker();
    component.selectReference(
      component.flatReferenceOptions.find((option) => option.expression === '$doc.name')!,
    );

    setCaret(surface.firstChild!, 0);
    animationFrames.at(-1)!(0);

    const selection = window.getSelection()!;
    expect((component as any).logicalOffsetAtRange(selection.getRangeAt(0))).toBe(
      'prefix-'.length + 1,
    );

    destroy();
    globalThis.requestAnimationFrame = originalRequestAnimationFrame;
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

  it('toggles the insert picker closed when the + button is clicked again', () => {
    const { component, surface, destroy } = createComponent(`'prefix-'`);
    setCaret(surface.firstChild!, 'prefix-'.length);
    component.onSurfaceFocus();

    component.onInsertMouseDown(new MouseEvent('mousedown', { cancelable: true }));
    component.onInsertClick(new MouseEvent('click', { detail: 1 }));
    expect(component.pickerOpen).toBe(true);

    component.onInsertMouseDown(new MouseEvent('mousedown', { cancelable: true }));
    component.onInsertClick(new MouseEvent('click', { detail: 1 }));
    expect(component.pickerOpen).toBe(false);
    destroy();
  });

  it('closes on pointer interaction outside the picker but not inside it', () => {
    const { component, root, destroy } = createComponent('');
    const picker = document.createElement('div');
    picker.className = 'smart-expression__picker';
    const pickerInput = document.createElement('input');
    picker.append(pickerInput);
    root.append(picker);
    component.pickerOpen = true;

    component.onDocumentMouseDown({ target: pickerInput } as unknown as MouseEvent);
    expect(component.pickerOpen).toBe(true);

    component.onDocumentMouseDown({ target: root } as unknown as MouseEvent);
    expect(component.pickerOpen).toBe(false);
    destroy();
  });

  it('focuses the picker search after rendering and positions it below the input', () => {
    const animationFrames: FrameRequestCallback[] = [];
    const originalRequestAnimationFrame = globalThis.requestAnimationFrame;
    globalThis.requestAnimationFrame = jest.fn((callback: FrameRequestCallback) => {
      animationFrames.push(callback);
      return animationFrames.length;
    });
    const { component, root, surface, destroy } = createComponent('');
    const pickerInput = document.createElement('input');
    root.append(pickerInput);
    (component as any).pickerSearch = { nativeElement: pickerInput };
    root.getBoundingClientRect = () => ({ top: 100, left: 20, bottom: 300 }) as DOMRect;
    surface.parentElement!.getBoundingClientRect = () =>
      ({ top: 124, left: 20, bottom: 164 }) as DOMRect;

    component.openInsertPicker();
    animationFrames.at(-1)!(0);

    expect(document.activeElement).toBe(pickerInput);
    expect(component.popoverTop).toBe(70);

    destroy();
    globalThis.requestAnimationFrame = originalRequestAnimationFrame;
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

  it('replaces only the clicked chip in a compound expression', () => {
    const { component, surface, destroy } = createComponent(
      `$pv.filename & '-' & $doc.address.street`,
    );
    const chips = surface.querySelectorAll<HTMLElement>('[data-expression-chip]');
    component.onSurfaceClick({
      target: chips[1],
      preventDefault: jest.fn(),
    } as unknown as MouseEvent);

    component.onReferenceMouseDown(
      new MouseEvent('mousedown', { bubbles: true, cancelable: true }),
      component.flatReferenceOptions.find((option) => option.expression === '$doc.name')!,
    );

    expect(component.expression).toBe(`$pv.filename & '-' & $doc.name`);
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

  it('keeps a complex expression in Advanced mode', () => {
    const { component, destroy } = createComponent('$uppercase($doc.name)');

    expect(component.rawRepresentable).toBe(false);
    component.switchToSimple();

    expect(component.mode).toBe('advanced');
    expect(component.expression).toBe('$uppercase($doc.name)');
    destroy();
  });

  it('uses one action to toggle between Visual and Advanced modes', () => {
    const { component, destroy } = createComponent(`"letter-" & $doc.name`);

    component.toggleMode();
    expect(component.mode).toBe('advanced');

    component.toggleMode();
    expect(component.mode).toBe('simple');
    destroy();
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
