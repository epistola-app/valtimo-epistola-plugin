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

import { CommonModule } from '@angular/common';
import { Component, forwardRef, Input, Pipe, PipeTransform } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { InputLabelModule, SelectItem, SelectModule } from '@valtimo/components';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { of } from 'rxjs';
import { SmartExpressionEditorComponent } from '../../../../frontend/plugin/src/lib/components/smart-expression-editor/smart-expression-editor.component';

@Pipe({ name: 'pluginTranslate', standalone: true })
class TestPluginTranslatePipe implements PipeTransform {
  transform(key: string) {
    return of(key);
  }
}

@Component({
  selector: 'v-input-label',
  standalone: true,
  template: `<span>{{ title }}</span>`,
})
class TestInputLabelComponent {
  @Input() name = '';
  @Input() title = '';
  @Input() tooltip = '';
  @Input() required = false;
  @Input() disabled = false;
}

@Component({
  selector: 'v-select',
  standalone: true,
  imports: [CommonModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => TestSelectComponent),
      multi: true,
    },
  ],
  template: `
    <label [for]="name">{{ title }}</label>
    <select
      [id]="name"
      [disabled]="disabled || loading"
      [value]="value"
      (change)="selectValue($any($event.target).value)"
    >
      <option value=""></option>
      <option *ngFor="let item of items" [value]="item.id">{{ item.text }}</option>
    </select>
  `,
})
class TestSelectComponent implements ControlValueAccessor {
  @Input() items: SelectItem[] = [];
  @Input() appendInline = false;
  @Input() dropUp = false;
  @Input() margin = false;
  @Input() disabled = false;
  @Input() loading = false;
  @Input() required = false;
  @Input() dataTestId = '';
  @Input() name = '';
  @Input() title = '';
  @Input() tooltip = '';

  value = '';
  private onChange: (value: string) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: string): void {
    this.value = value || '';
  }

  registerOnChange(callback: (value: string) => void): void {
    this.onChange = callback;
  }

  registerOnTouched(callback: () => void): void {
    this.onTouched = callback;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled = disabled;
  }

  selectValue(value: string): void {
    this.value = value;
    this.onChange(value);
    this.onTouched();
  }
}

describe('SmartExpressionEditorComponent integration', () => {
  let fixture: ComponentFixture<SmartExpressionEditorComponent>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [SmartExpressionEditorComponent, FormsModule],
    });
    TestBed.overrideComponent(SmartExpressionEditorComponent, {
      remove: {
        imports: [InputLabelModule, SelectModule, PluginTranslatePipeModule],
      },
      add: {
        imports: [TestInputLabelComponent, TestSelectComponent, TestPluginTranslatePipe],
      },
    });
    await TestBed.compileComponents();
  });

  async function render(expression: string): Promise<SmartExpressionEditorComponent> {
    fixture = TestBed.createComponent(SmartExpressionEditorComponent);
    fixture.componentRef.setInput('title', 'Environment');
    fixture.componentRef.setInput('tooltip', 'Choose an environment');
    fixture.componentRef.setInput('testId', 'environment-expression');
    fixture.componentRef.setInput('contextVariables', { pv: ['environment'] });
    fixture.componentRef.setInput('selectOptions', [
      { id: 'default', text: 'Default' },
      { id: 'production', text: 'Production' },
    ]);
    fixture.componentRef.setInput('expression', expression);
    fixture.detectChanges();
    await settle();
    return fixture.componentInstance;
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('renders and updates an exact expression through the labelled Select view', async () => {
    const component = await render('"production"');
    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    const label = fixture.nativeElement.querySelector('label') as HTMLLabelElement;
    const modeButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    const emitted = jasmine.createSpy('expressionChange');
    component.expressionChange.subscribe(emitted);

    expect(component.mode).toBe('select');
    expect(label.htmlFor).toBe('environment-expression-select');
    expect(select.id).toBe('environment-expression-select');
    expect(select.value).toBe('production');
    expect(getComputedStyle(modeButton).height).toBe('40px');

    select.value = 'default';
    select.dispatchEvent(new Event('change'));
    await settle();

    expect(emitted).toHaveBeenCalledWith('"default"');
  });

  it('cycles Select, Visual, and Advanced without changing the expression', async () => {
    const component = await render('"production"');
    const original = component.expression;

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await settle();
    expect(component.mode).toBe('simple');
    expect(component.expression).toBe(original);
    expect(fixture.nativeElement.querySelector('[role="textbox"]')).not.toBeNull();

    (fixture.nativeElement.querySelector('button:last-of-type') as HTMLButtonElement).click();
    await settle();
    expect(component.mode).toBe('advanced');
    expect(component.expression).toBe(original);
    expect(fixture.nativeElement.querySelector('textarea')).not.toBeNull();

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await settle();
    expect(component.mode).toBe('select');
    expect(component.expression).toBe(original);
  });

  it('skips Select for an unmatched dynamic expression', async () => {
    const component = await render('$pv.environment');
    expect(component.mode).toBe('simple');

    (fixture.nativeElement.querySelector('button:last-of-type') as HTMLButtonElement).click();
    await settle();
    expect(component.mode).toBe('advanced');

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await settle();
    expect(component.mode).toBe('simple');
  });

  it('associates an invalid Advanced expression with its label and error', async () => {
    const component = await render('$doc.[broken');
    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    const label = fixture.nativeElement.querySelector('v-input-label') as HTMLElement;
    const error = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    const modeButton = fixture.nativeElement.querySelector('button') as HTMLButtonElement;

    expect(component.mode).toBe('advanced');
    expect(label.id).toBe('environment-expression-label');
    expect(textarea.id).toBe('environment-expression-advanced');
    expect(textarea.getAttribute('aria-labelledby')).toBe('environment-expression-label');
    expect(textarea.getAttribute('aria-invalid')).toBe('true');
    expect(textarea.getAttribute('aria-errormessage')).toBe('environment-expression-error');
    expect(textarea.getAttribute('aria-describedby')).toBe('environment-expression-error');
    expect(error.id).toBe('environment-expression-error');
    expect(modeButton.disabled).toBe(true);
  });

  it('renders, expands, and inserts a schema-backed function field', async () => {
    const component = await render('');
    fixture.componentRef.setInput('functions', [
      {
        name: 'resident',
        description: 'Loads a resident',
        overloads: [
          {
            arguments: [],
            returnType: 'Map',
            resultSchema: {
              type: 'object',
              required: ['person'],
              properties: {
                person: {
                  type: 'object',
                  required: ['name'],
                  properties: {
                    name: { type: ['string', 'null'], description: 'Full name' },
                  },
                },
              },
            },
          },
        ],
      },
    ]);
    await settle();
    if (component.mode === 'select') {
      component.toggleMode();
      await settle();
    }

    (
      fixture.nativeElement.querySelector(
        '[data-testid="environment-expression-insert"]',
      ) as HTMLButtonElement
    ).click();
    await settle();

    const group = fixture.nativeElement.querySelector(
      '.function-reference__group',
    ) as HTMLElement;
    const expand = group.querySelector('.function-reference__expand') as HTMLButtonElement;
    expect(group.textContent).toContain('$resident()');
    expect(group.textContent).toContain('person');
    expect(group.textContent).toContain('required');
    expect(expand.getAttribute('aria-expanded')).toBe('false');

    expand.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
    await settle();

    expect(expand.getAttribute('aria-expanded')).toBe('true');
    expect(group.textContent).toContain('Full name');
    expect(group.textContent).toContain('nullable');

    const nameOption = Array.from(
      group.querySelectorAll<HTMLButtonElement>('.function-reference__option'),
    ).find((option) => option.textContent?.includes('Full name'))!;
    nameOption.click();
    await settle();

    expect(component.expression).toBe('$resident().person.name');
  });
});
