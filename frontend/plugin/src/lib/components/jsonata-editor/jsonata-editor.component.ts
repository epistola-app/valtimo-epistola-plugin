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

import {
  AfterViewInit,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { EditorModule } from '@valtimo/components';
import { Subject, debounceTime, takeUntil } from 'rxjs';
import { ExpressionFunctionInfo } from '../../models';
import { jsonataCompletionData, registerJsonataLanguage } from '../../utils/jsonata-monaco';

import * as _jsonata from 'jsonata';
const jsonata = (_jsonata as any).default || _jsonata;
let nextEditorModelId = 0;

@Component({
  selector: 'epistola-jsonata-editor',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, EditorModule],
  template: `
    <div class="jsonata-editor" data-testid="epistola-jsonata-editor">
      <valtimo-editor
        data-testid="epistola-jsonata-editor-input"
        [model]="editorModel"
        [editorOptions]="editorOptions"
        [disabled]="disabled"
        [heightPx]="250"
        [formatOnLoad]="false"
        (valueChangeEvent)="onEditorValueChange($event)"
      ></valtimo-editor>
      <div class="jsonata-editor__footer" data-testid="epistola-jsonata-footer">
        <span *ngIf="error" class="jsonata-editor__error" data-testid="epistola-jsonata-error">{{
          error
        }}</span>
        <span
          *ngIf="!error && expression"
          class="jsonata-editor__valid"
          data-testid="epistola-jsonata-valid"
          >&#x2713;</span
        >
        <span class="jsonata-editor__variables" data-testid="epistola-jsonata-variables-hint">{{
          variablesHint
        }}</span>
      </div>
    </div>
  `,
  styles: [
    `
      .jsonata-editor__footer {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 4px;
        font-size: 0.8em;
      }
      .jsonata-editor__error {
        color: #da1e28;
      }
      .jsonata-editor__valid {
        color: #198038;
      }
      .jsonata-editor__variables {
        margin-left: auto;
        color: #8d8d8d;
        font-family: monospace;
      }
    `,
  ],
})
export class JsonataEditorComponent implements OnChanges, AfterViewInit, OnDestroy {
  @Input() expression: string = '';
  @Input() disabled: boolean = false;
  /**
   * Context variables in scope, keyed by name (without `$`), each mapping to its
   * field/path suggestions — e.g. `{ doc: [...], pv: [...] }` for the data
   * mapping, `{ form: [...] }` for the override builder. Drives both the
   * `$`-variable list and `$<name>.` field completion.
   */
  @Input() contextVariables: Record<string, string[]> = {};
  @Input() functions: ExpressionFunctionInfo[] = [];
  /** Footer hint listing the context variables in scope. */
  @Input() variablesHint: string = '$doc · $pv · $case';
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  private readonly modelUri = `inmemory://epistola/jsonata-${++nextEditorModelId}.jsonata`;
  editorModel: { value: string; language: string; uri: string } = {
    value: '',
    language: 'jsonata',
    uri: this.modelUri,
  };
  editorOptions: Record<string, any> = {
    minimap: { enabled: false },
    lineNumbers: 'on',
    scrollBeyondLastLine: false,
    fontSize: 13,
    tabSize: 2,
    wordWrap: 'on',
    renderWhitespace: 'none',
  };

  error: string | null = null;

  private destroy$ = new Subject<void>();
  private validate$ = new Subject<string>();
  private lastEmittedExpression: string | null = null;
  private languageRegistered = false;
  private languageRegistrationFrame: number | null = null;
  private destroyed = false;

  constructor() {
    this.validate$.pipe(debounceTime(300), takeUntil(this.destroy$)).subscribe((value) => {
      this.validateExpression(value);
    });

    // Try to register language eagerly if Monaco is already loaded
    this.tryRegisterLanguage();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['expression']) {
      const next = this.expression || '';
      if (next === this.lastEmittedExpression) {
        this.lastEmittedExpression = null;
      } else {
        this.lastEmittedExpression = null;
        if (next !== this.editorModel.value) {
          this.editorModel = { value: next, language: 'jsonata', uri: this.modelUri };
        }
        this.validate$.next(next);
      }
    }
    if (changes['contextVariables']) {
      jsonataCompletionData.variables = this.contextVariables || {};
    }
    if (changes['functions']) {
      jsonataCompletionData.functions = this.functions;
    }
  }

  ngAfterViewInit(): void {
    this.ensureLanguageRegistered();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.languageRegistrationFrame !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(this.languageRegistrationFrame);
    }
    this.destroy$.next();
    this.destroy$.complete();
  }

  onEditorValueChange(value: string): void {
    // Register language on first editor event (Monaco is now loaded)
    this.ensureLanguageRegistered();
    if (value === this.expression) return;
    this.expression = value;
    this.editorModel.value = value;
    this.lastEmittedExpression = value;
    this.expressionChange.emit(value);
    this.validate$.next(value);
  }

  private ensureLanguageRegistered(): void {
    if (this.languageRegistered || this.destroyed) return;
    if (this.tryRegisterLanguage()) return;
    if (typeof requestAnimationFrame === 'function') {
      this.languageRegistrationFrame = requestAnimationFrame(() => {
        this.languageRegistrationFrame = null;
        this.ensureLanguageRegistered();
      });
    }
  }

  private tryRegisterLanguage(): boolean {
    const m = (window as any).monaco;
    if (!m) return false;

    registerJsonataLanguage(m);
    this.languageRegistered = true;
    jsonataCompletionData.variables = this.contextVariables || {};
    jsonataCompletionData.functions = this.functions;
    this.refreshModelLanguage(m);
    return true;
  }

  private refreshModelLanguage(monacoInstance: any): void {
    const model = monacoInstance.editor?.getModel(monacoInstance.Uri.parse(this.modelUri));
    if (!model) return;

    if (model.getLanguageId?.() === 'jsonata') {
      monacoInstance.editor.setModelLanguage(model, 'plaintext');
    }
    monacoInstance.editor.setModelLanguage(model, 'jsonata');
  }

  private validateExpression(value: string): void {
    if (!value || !value.trim()) {
      this.error = null;
      this.validChange.emit(true);
      return;
    }
    try {
      jsonata(value);
      this.error = null;
      this.validChange.emit(true);
    } catch (e: any) {
      this.error = e.message || 'Invalid expression';
      this.validChange.emit(false);
    }
  }
}
