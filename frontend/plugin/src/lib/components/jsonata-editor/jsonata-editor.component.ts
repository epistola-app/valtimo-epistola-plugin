import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import * as jsonata from 'jsonata';

@Component({
  selector: 'epistola-jsonata-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule],
  template: `
    <div class="jsonata-editor">
      <label class="jsonata-editor__label">
        {{ 'dataMappingTitle' | pluginTranslate: 'epistola' | async }}
      </label>
      <p class="jsonata-editor__description">
        {{ 'jsonataDescription' | pluginTranslate: 'epistola' | async }}
      </p>
      <textarea
        class="jsonata-editor__textarea"
        [class.jsonata-editor__textarea--error]="error"
        [ngModel]="expression"
        (ngModelChange)="onExpressionChange($event)"
        [disabled]="disabled"
        [placeholder]="placeholder"
        rows="12"
        spellcheck="false"
      ></textarea>
      <div class="jsonata-editor__footer">
        <span *ngIf="error" class="jsonata-editor__error">{{ error }}</span>
        <span *ngIf="!error && expression" class="jsonata-editor__valid">&#x2713;</span>
        <span class="jsonata-editor__variables">$doc · $pv · $case</span>
      </div>
    </div>
  `,
  styles: [
    `
      .jsonata-editor__label {
        font-weight: 600;
        margin-bottom: 4px;
        display: block;
      }
      .jsonata-editor__description {
        font-size: 0.85em;
        color: #6f6f6f;
        margin-bottom: 8px;
      }
      .jsonata-editor__textarea {
        width: 100%;
        font-family: 'IBM Plex Mono', 'Menlo', 'Consolas', monospace;
        font-size: 13px;
        line-height: 1.5;
        padding: 12px;
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        resize: vertical;
        background: #f4f4f4;
      }
      .jsonata-editor__textarea:focus {
        outline: 2px solid #0f62fe;
        border-color: #0f62fe;
        background: #ffffff;
      }
      .jsonata-editor__textarea--error {
        border-color: #da1e28;
      }
      .jsonata-editor__textarea--error:focus {
        outline-color: #da1e28;
        border-color: #da1e28;
      }
      .jsonata-editor__textarea:disabled {
        opacity: 0.6;
        cursor: not-allowed;
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
export class JsonataEditorComponent implements OnChanges {
  @Input() expression: string = '';
  @Input() disabled: boolean = false;
  @Input() placeholder: string = '{\n  "field": $doc.path.to.value\n}';
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  error: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['expression']) {
      this.validate(this.expression);
    }
  }

  onExpressionChange(value: string): void {
    this.expression = value;
    this.validate(value);
    this.expressionChange.emit(value);
  }

  private validate(value: string): void {
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
