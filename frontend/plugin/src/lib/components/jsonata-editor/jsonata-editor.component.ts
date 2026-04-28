import {
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
import { ExpressionFunctionInfo, VariableSuggestions } from '../../models';
import { jsonataCompletionData, registerJsonataLanguage } from '../../utils/jsonata-monaco';

import * as _jsonata from 'jsonata';
const jsonata = (_jsonata as any).default || _jsonata;

@Component({
  selector: 'epistola-jsonata-editor',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, EditorModule],
  template: `
    <div class="jsonata-editor">
      <valtimo-editor
        [model]="editorModel"
        [editorOptions]="editorOptions"
        [disabled]="disabled"
        [heightPx]="250"
        [formatOnLoad]="false"
        (valueChangeEvent)="onEditorValueChange($event)"
      ></valtimo-editor>
      <div class="jsonata-editor__footer">
        <span *ngIf="error" class="jsonata-editor__error">{{ error }}</span>
        <span *ngIf="!error && expression" class="jsonata-editor__valid">&#x2713;</span>
        <span class="jsonata-editor__variables">$doc · $pv · $case</span>
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
export class JsonataEditorComponent implements OnChanges, OnDestroy {
  @Input() expression: string = '';
  @Input() disabled: boolean = false;
  @Input() suggestions: VariableSuggestions | null = null;
  @Input() functions: ExpressionFunctionInfo[] = [];
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  editorModel: { value: string; language: string } = { value: '', language: 'jsonata' };
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
  private suppressChange = false;
  private languageRegistered = false;

  constructor() {
    this.validate$.pipe(debounceTime(300), takeUntil(this.destroy$)).subscribe((value) => {
      this.validateExpression(value);
    });

    // Try to register language eagerly if Monaco is already loaded
    this.tryRegisterLanguage();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['expression'] && !this.suppressChange) {
      this.editorModel = { value: this.expression || '', language: 'jsonata' };
      this.validate$.next(this.expression);
    }
    if (changes['suggestions']) {
      jsonataCompletionData.suggestions = this.suggestions;
    }
    if (changes['functions']) {
      jsonataCompletionData.functions = this.functions;
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onEditorValueChange(value: string): void {
    // Register language on first editor event (Monaco is now loaded)
    if (!this.languageRegistered) {
      this.tryRegisterLanguage();
    }

    if (this.suppressChange) return;
    this.suppressChange = true;
    this.expression = value;
    this.expressionChange.emit(value);
    this.validate$.next(value);
    setTimeout(() => (this.suppressChange = false));
  }

  private tryRegisterLanguage(): void {
    const m = (window as any).monaco;
    if (m) {
      registerJsonataLanguage(m);
      this.languageRegistered = true;
      jsonataCompletionData.suggestions = this.suggestions;
      jsonataCompletionData.functions = this.functions;
    }
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
