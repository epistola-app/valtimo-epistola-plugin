import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { Subject, debounceTime, takeUntil } from 'rxjs';
import { ExpressionFunctionInfo, VariableSuggestions } from '../../models';
import { jsonataCompletionData, registerJsonataLanguage } from '../../utils/jsonata-monaco';

import * as _jsonata from 'jsonata';
const jsonata = (_jsonata as any).default || _jsonata;

// Monaco types (loaded at runtime)
declare const monaco: any;

@Component({
  selector: 'epistola-jsonata-editor',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule],
  template: `
    <div class="jsonata-editor">
      <div #editorContainer class="jsonata-editor__container"></div>
      <div class="jsonata-editor__footer">
        <span *ngIf="error" class="jsonata-editor__error">{{ error }}</span>
        <span *ngIf="!error && expression" class="jsonata-editor__valid">&#x2713;</span>
        <span class="jsonata-editor__variables">$doc · $pv · $case</span>
      </div>
    </div>
  `,
  styles: [
    `
      .jsonata-editor__container {
        width: 100%;
        height: 250px;
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        overflow: hidden;
      }
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
  @ViewChild('editorContainer') editorContainer!: ElementRef;

  @Input() expression: string = '';
  @Input() disabled: boolean = false;
  @Input() suggestions: VariableSuggestions | null = null;
  @Input() functions: ExpressionFunctionInfo[] = [];
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  error: string | null = null;

  private editor: any = null;
  private destroy$ = new Subject<void>();
  private validate$ = new Subject<string>();
  private suppressChange = false;

  ngAfterViewInit(): void {
    this.initMonaco();
    this.validate$.pipe(debounceTime(300), takeUntil(this.destroy$)).subscribe((value) => {
      this.validateExpression(value);
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['expression'] && this.editor && !this.suppressChange) {
      const currentValue = this.editor.getValue();
      if (currentValue !== this.expression) {
        this.suppressChange = true;
        this.editor.setValue(this.expression || '');
        this.suppressChange = false;
      }
    }
    if (changes['suggestions']) {
      jsonataCompletionData.suggestions = this.suggestions;
    }
    if (changes['functions']) {
      jsonataCompletionData.functions = this.functions;
    }
    if (changes['disabled'] && this.editor) {
      this.editor.updateOptions({ readOnly: this.disabled });
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.editor) {
      this.editor.dispose();
    }
  }

  private initMonaco(): void {
    // Monaco is loaded globally by Valtimo's asset configuration
    const monacoInterval = setInterval(() => {
      if (typeof monaco !== 'undefined') {
        clearInterval(monacoInterval);
        this.createEditor();
      }
    }, 100);

    // Timeout after 5s
    setTimeout(() => clearInterval(monacoInterval), 5000);
  }

  private createEditor(): void {
    registerJsonataLanguage(monaco);

    // Update completion data
    jsonataCompletionData.suggestions = this.suggestions;
    jsonataCompletionData.functions = this.functions;

    this.editor = monaco.editor.create(this.editorContainer.nativeElement, {
      value: this.expression || '',
      language: 'jsonata',
      theme: 'vs',
      minimap: { enabled: false },
      lineNumbers: 'on',
      scrollBeyondLastLine: false,
      automaticLayout: true,
      fontSize: 13,
      fontFamily: "'IBM Plex Mono', 'Menlo', 'Consolas', monospace",
      tabSize: 2,
      readOnly: this.disabled,
      wordWrap: 'on',
      renderWhitespace: 'none',
      overviewRulerLanes: 0,
      hideCursorInOverviewRuler: true,
      scrollbar: {
        vertical: 'auto',
        horizontal: 'hidden',
      },
    });

    this.editor.onDidChangeModelContent(() => {
      if (this.suppressChange) return;
      const value = this.editor.getValue();
      this.suppressChange = true;
      this.expressionChange.emit(value);
      this.suppressChange = false;
      this.validate$.next(value);
    });

    // Initial validation
    if (this.expression) {
      this.validate$.next(this.expression);
    }
  }

  private validateExpression(value: string): void {
    if (!value || !value.trim()) {
      this.error = null;
      this.validChange.emit(true);
      this.clearMarkers();
      return;
    }
    try {
      jsonata(value);
      this.error = null;
      this.validChange.emit(true);
      this.clearMarkers();
    } catch (e: any) {
      this.error = e.message || 'Invalid expression';
      this.validChange.emit(false);
      this.setErrorMarker(e);
    }
  }

  private setErrorMarker(error: any): void {
    if (!this.editor) return;
    const model = this.editor.getModel();
    if (!model) return;

    // JSONata errors have a `position` (character offset)
    const position = error.position || 0;
    const pos = model.getPositionAt(position);

    monaco.editor.setModelMarkers(model, 'jsonata', [
      {
        startLineNumber: pos.lineNumber,
        startColumn: pos.column,
        endLineNumber: pos.lineNumber,
        endColumn: pos.column + 1,
        message: error.message || 'Syntax error',
        severity: monaco.MarkerSeverity.Error,
      },
    ]);
  }

  private clearMarkers(): void {
    if (!this.editor) return;
    const model = this.editor.getModel();
    if (model) {
      monaco.editor.setModelMarkers(model, 'jsonata', []);
    }
  }
}
