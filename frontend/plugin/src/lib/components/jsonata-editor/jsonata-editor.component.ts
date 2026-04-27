import {
  AfterViewInit,
  Component,
  EventEmitter,
  Inject,
  Input,
  OnChanges,
  OnDestroy,
  Optional,
  Output,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { EditorModule } from '@valtimo/components';
import { first, Subject, debounceTime, takeUntil } from 'rxjs';
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
        (valueChangeEvent)="onValueChange($event)"
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
export class JsonataEditorComponent implements AfterViewInit, OnChanges, OnDestroy {
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
  private editorService: any;

  constructor(@Optional() @Inject('EditorService') editorServiceToken: any) {
    // Try to get EditorService — it's provided by Valtimo at the app level
    this.editorService = editorServiceToken;

    this.validate$.pipe(debounceTime(300), takeUntil(this.destroy$)).subscribe((value) => {
      this.validateExpression(value);
    });
  }

  ngAfterViewInit(): void {
    // Register JSONata language once Monaco is loaded
    // Valtimo's EditorComponent uses EditorService internally to load Monaco.
    // By the time our ngAfterViewInit runs and Valtimo's editor renders,
    // Monaco will be available as a global. Register on next tick to ensure
    // Valtimo's editor has initialized first.
    setTimeout(() => this.registerLanguage());
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

  onValueChange(value: string): void {
    this.suppressChange = true;
    this.expression = value;
    this.expressionChange.emit(value);
    this.validate$.next(value);
    setTimeout(() => (this.suppressChange = false));
  }

  private registerLanguage(): void {
    const m = (window as any).monaco;
    if (m) {
      registerJsonataLanguage(m);
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
