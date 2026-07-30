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
import {
  Component,
  EventEmitter,
  forwardRef,
  Input,
  Output,
  Pipe,
  PipeTransform,
} from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { SelectItem, SelectModule } from '@valtimo/components';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { of } from 'rxjs';
import { ExpressionSelectEditorComponent } from '../../../../frontend/plugin/src/lib/components/expression-select-editor/expression-select-editor.component';

@Pipe({ name: 'pluginTranslate', standalone: true })
class TestPluginTranslatePipe implements PipeTransform {
  transform(key: string) {
    return of(key);
  }
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
  @Output() selectedChange = new EventEmitter<string>();

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
    this.selectedChange.emit(value);
  }
}

describe('ExpressionSelectEditorComponent integration', () => {
  let fixture: ComponentFixture<ExpressionSelectEditorComponent>;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [ExpressionSelectEditorComponent, FormsModule],
    });
    TestBed.overrideComponent(ExpressionSelectEditorComponent, {
      remove: {
        imports: [SelectModule, PluginTranslatePipeModule],
      },
      add: {
        imports: [TestSelectComponent, TestPluginTranslatePipe],
      },
    });
    await TestBed.compileComponents();
  });

  async function render(expression: string): Promise<ExpressionSelectEditorComponent> {
    fixture = TestBed.createComponent(ExpressionSelectEditorComponent);
    fixture.componentRef.setInput('title', 'Environment');
    fixture.componentRef.setInput('tooltip', 'Choose an environment');
    fixture.componentRef.setInput('testId', 'environment-expression');
    fixture.componentRef.setInput('items', [
      { id: 'default', text: 'Default' },
      { id: 'production', text: 'Production' },
    ]);
    fixture.componentRef.setInput('expression', expression);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('renders an exact expression through the labelled select and persists a selection', async () => {
    const component = await render('"production"');
    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    const label = fixture.nativeElement.querySelector('label') as HTMLLabelElement;
    const emitted = jasmine.createSpy('expressionChange');
    component.expressionChange.subscribe(emitted);

    expect(component.mode).toBe('select');
    expect(label.htmlFor).toBe('environment-expression-select');
    expect(select.id).toBe('environment-expression-select');
    expect(select.value).toBe('production');

    select.value = 'default';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(emitted).toHaveBeenCalledWith('"default"');
  });

  it('associates an invalid Advanced expression with its label and error', async () => {
    await render('$doc.[broken');
    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    const label = fixture.nativeElement.querySelector('label') as HTMLLabelElement;
    const error = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    const modeButton = fixture.debugElement.query(By.css('button'))
      .nativeElement as HTMLButtonElement;

    expect(label.htmlFor).toBe('environment-expression-advanced');
    expect(textarea.id).toBe('environment-expression-advanced');
    expect(textarea.getAttribute('aria-invalid')).toBe('true');
    expect(textarea.getAttribute('aria-errormessage')).toBe('environment-expression-error');
    expect(textarea.getAttribute('aria-describedby')).toBe('environment-expression-error');
    expect(error.id).toBe('environment-expression-error');
    expect(modeButton.getAttribute('aria-describedby')).toBe('environment-expression-error');
    expect(modeButton.disabled).toBe(true);
  });

  it('returns to the dropdown only after the expression exactly matches an option', async () => {
    const component = await render('$pv.environment');
    const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    const modeButton = fixture.debugElement.query(By.css('button'))
      .nativeElement as HTMLButtonElement;

    expect(modeButton.disabled).toBe(true);
    expect(textarea.getAttribute('aria-describedby')).toBe(
      'environment-expression-expression-hint',
    );

    textarea.value = '"default"';
    textarea.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.canUseSelect).toBe(true);
    expect(modeButton.disabled).toBe(false);
    modeButton.click();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.mode).toBe('select');
    expect((fixture.nativeElement.querySelector('select') as HTMLSelectElement).value).toBe(
      'default',
    );
  });
});
