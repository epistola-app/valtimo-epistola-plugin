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

import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SelectedValue, SelectItem, SelectModule } from '@valtimo/components';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import * as _jsonata from 'jsonata';
import {
  decodeJsonataStringLiteral,
  encodeJsonataStringLiteral,
} from '../generate-document-configuration/generate-document-config-version';

const jsonata = (_jsonata as any).default || _jsonata;

@Component({
  selector: 'epistola-expression-select-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectModule, PluginTranslatePipeModule],
  templateUrl: './expression-select-editor.component.html',
  styleUrls: ['./expression-select-editor.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExpressionSelectEditorComponent implements OnChanges {
  @Input() expression = '';
  @Input() items: SelectItem[] = [];
  @Input() title = '';
  @Input() tooltip = '';
  @Input() placeholder = '';
  @Input() disabled = false;
  @Input() loading = false;
  @Input() testId = 'epistola-expression-select';
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  @ViewChild('rawTextarea') private rawTextarea?: ElementRef<HTMLTextAreaElement>;

  mode: 'select' | 'advanced' = 'select';
  selectedValue = '';
  rawExpression = '';
  rawError: string | null = null;

  private modeChosenByUser = false;
  private lastEmittedExpression: string | null = null;
  private lastValidity: boolean | null = null;

  constructor(private readonly cdr: ChangeDetectorRef) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['expression']) {
      if (this.expression === this.lastEmittedExpression) {
        this.lastEmittedExpression = null;
      } else {
        this.rawExpression = this.expression || '';
        this.modeChosenByUser = false;
      }
    }

    if ((changes['expression'] || changes['items']) && !this.modeChosenByUser) {
      this.useBestView();
    }

    if (changes['expression']) {
      this.validate(this.expression || '');
    }
  }

  get canUseSelect(): boolean {
    if (!this.rawExpression.trim()) return true;
    const literal = decodeJsonataStringLiteral(this.rawExpression);
    return literal !== undefined && this.items.some((option) => String(option.id) === literal);
  }

  onSelectedValueChange(value: SelectedValue | undefined): void {
    this.selectedValue = value == null || Array.isArray(value) ? '' : String(value);
    this.rawExpression = this.selectedValue ? encodeJsonataStringLiteral(this.selectedValue) : '';
    this.emitExpression(this.rawExpression);
    this.validate(this.rawExpression);
  }

  onRawInput(value: string, textarea: HTMLTextAreaElement): void {
    this.rawExpression = value;
    this.resizeTextarea(textarea);
    this.emitExpression(value);
    this.validate(value);
  }

  toggleMode(): void {
    if (this.mode === 'advanced') {
      if (!this.canUseSelect) return;
      this.selectedValue = decodeJsonataStringLiteral(this.rawExpression) || '';
      this.mode = 'select';
    } else {
      this.rawExpression = this.selectedValue
        ? encodeJsonataStringLiteral(this.selectedValue)
        : this.rawExpression;
      this.mode = 'advanced';
      queueMicrotask(() => {
        const textarea = this.rawTextarea?.nativeElement;
        if (textarea) {
          this.resizeTextarea(textarea);
          textarea.focus();
          textarea.setSelectionRange(textarea.value.length, textarea.value.length);
        }
      });
    }
    this.modeChosenByUser = true;
    this.cdr.markForCheck();
  }

  private useBestView(): void {
    const literal = decodeJsonataStringLiteral(this.rawExpression);
    const exactMatch =
      literal !== undefined && this.items.some((option) => String(option.id) === literal);

    if (exactMatch) {
      this.selectedValue = literal;
      this.mode = 'select';
    } else if (this.rawExpression) {
      this.selectedValue = '';
      this.mode = 'advanced';
    } else {
      this.selectedValue = '';
      this.mode = 'select';
    }
    this.cdr.markForCheck();
  }

  private emitExpression(value: string): void {
    this.lastEmittedExpression = value;
    this.expressionChange.emit(value);
  }

  private validate(value: string): void {
    if (!value.trim()) {
      this.rawError = null;
      this.emitValidity(true);
      return;
    }

    try {
      jsonata(value);
      this.rawError = null;
      this.emitValidity(true);
    } catch (error: any) {
      this.rawError = error?.message || 'Invalid JSONata expression';
      this.emitValidity(false);
    }
    this.cdr.markForCheck();
  }

  private emitValidity(valid: boolean): void {
    if (valid === this.lastValidity) return;
    this.lastValidity = valid;
    this.validChange.emit(valid);
  }

  private resizeTextarea(textarea: HTMLTextAreaElement): void {
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 240)}px`;
  }
}
