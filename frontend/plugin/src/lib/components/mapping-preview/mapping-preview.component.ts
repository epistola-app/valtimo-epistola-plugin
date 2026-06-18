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
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { Subject, debounceTime, takeUntil } from 'rxjs';
import { EvaluationResult, TemplateField } from '../../models';
import { EpistolaPluginService } from '../../services';

@Component({
  selector: 'epistola-mapping-preview',
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule],
  template: `
    <div class="preview">
      <div class="preview__header">
        <span class="preview__title">{{
          'previewTitle' | pluginTranslate: 'epistola' | async
        }}</span>
        <div class="preview__controls">
          <input
            type="text"
            class="preview__doc-input"
            [ngModel]="documentId"
            (ngModelChange)="documentId = $event"
            [placeholder]="'previewDocPlaceholder' | pluginTranslate: 'epistola' | async"
          />
          <button
            class="preview__run-btn"
            (click)="runPreview()"
            [disabled]="!documentId || !expression || loading"
          >
            &#x25B6;
          </button>
        </div>
      </div>

      <div class="preview__panels">
        <!-- Expected structure -->
        <div class="preview__panel">
          <div class="preview__panel-label">
            {{ 'previewExpected' | pluginTranslate: 'epistola' | async }}
          </div>
          <pre class="preview__code">{{ expectedJson }}</pre>
        </div>

        <!-- Produced output -->
        <div class="preview__panel">
          <div class="preview__panel-label">
            {{ 'previewProduced' | pluginTranslate: 'epistola' | async }}
          </div>
          <div *ngIf="loading" class="preview__loading">...</div>
          <pre *ngIf="!loading && result?.success" class="preview__code">{{
            result.result | json
          }}</pre>
          <div *ngIf="!loading && result && !result.success" class="preview__error">
            {{ result.error }}
          </div>
          <div *ngIf="!loading && !result" class="preview__placeholder">
            {{ 'previewRunHint' | pluginTranslate: 'epistola' | async }}
          </div>
        </div>
      </div>

      <!-- Missing fields warning -->
      <div *ngIf="missingRequired.length > 0" class="preview__warnings">
        <span class="preview__warning-icon">&#x26A0;</span>
        {{ 'previewMissing' | pluginTranslate: 'epistola' | async }}:
        <strong>{{ missingRequired.join(', ') }}</strong>
      </div>
    </div>
  `,
  styles: [
    `
      .preview {
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        margin-top: 16px;
        overflow: hidden;
      }
      .preview__header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 8px 12px;
        background: #f4f4f4;
        border-bottom: 1px solid #e0e0e0;
      }
      .preview__title {
        font-weight: 600;
        font-size: 0.85em;
      }
      .preview__controls {
        display: flex;
        gap: 4px;
      }
      .preview__doc-input {
        padding: 4px 8px;
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        font-size: 0.8em;
        width: 220px;
        font-family: monospace;
      }
      .preview__run-btn {
        padding: 4px 10px;
        border: 1px solid #0f62fe;
        border-radius: 4px;
        background: #0f62fe;
        color: white;
        cursor: pointer;
        font-size: 0.8em;
      }
      .preview__run-btn:disabled {
        opacity: 0.4;
        cursor: not-allowed;
      }
      .preview__panels {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 1px;
        background: #e0e0e0;
      }
      .preview__panel {
        background: white;
        padding: 8px 12px;
        min-height: 80px;
      }
      .preview__panel-label {
        font-size: 0.75em;
        color: #6f6f6f;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        margin-bottom: 4px;
      }
      .preview__code {
        font-family: 'IBM Plex Mono', monospace;
        font-size: 0.8em;
        line-height: 1.4;
        margin: 0;
        white-space: pre-wrap;
        word-break: break-word;
      }
      .preview__loading {
        color: #8d8d8d;
      }
      .preview__error {
        color: #da1e28;
        font-size: 0.85em;
      }
      .preview__placeholder {
        color: #8d8d8d;
        font-size: 0.85em;
        font-style: italic;
      }
      .preview__warnings {
        padding: 8px 12px;
        background: #fff8e1;
        border-top: 1px solid #e0e0e0;
        font-size: 0.85em;
        color: #663c00;
      }
      .preview__warning-icon {
        margin-right: 4px;
      }
    `,
  ],
})
export class MappingPreviewComponent implements OnChanges, OnDestroy {
  @Input() expression: string = '';
  @Input() templateFields: TemplateField[] = [];
  @Input() caseDefinitionKey: string | null = null;

  documentId: string = '';
  loading = false;
  result: EvaluationResult | null = null;
  expectedJson: string = '';
  missingRequired: string[] = [];

  private destroy$ = new Subject<void>();
  private evaluate$ = new Subject<void>();

  constructor(private readonly epistolaPluginService: EpistolaPluginService) {
    this.evaluate$.pipe(debounceTime(300), takeUntil(this.destroy$)).subscribe(() => {
      this.doEvaluate();
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['templateFields']) {
      this.expectedJson = this.buildExpectedJson();
    }
    if (changes['expression'] || changes['templateFields']) {
      this.checkMissingRequired();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  runPreview(): void {
    this.evaluate$.next();
  }

  private doEvaluate(): void {
    if (!this.documentId || !this.expression) return;
    this.loading = true;
    this.epistolaPluginService.evaluateMapping(this.expression, this.documentId).subscribe({
      next: (result) => {
        this.result = result;
        this.loading = false;
        if (result.success) {
          this.checkMissingRequired();
        }
      },
      error: (err) => {
        this.result = { success: false, result: null, error: err.message || 'Request failed' };
        this.loading = false;
      },
    });
  }

  private buildExpectedJson(): string {
    if (!this.templateFields || this.templateFields.length === 0) return '{}';
    const obj: Record<string, string> = {};
    for (const field of this.templateFields) {
      const type = field.type || 'any';
      obj[field.name] = field.required ? `${type} (required)` : type;
    }
    return JSON.stringify(obj, null, 2);
  }

  private checkMissingRequired(): void {
    if (!this.templateFields) {
      this.missingRequired = [];
      return;
    }
    const requiredFields = this.templateFields.filter((f) => f.required).map((f) => f.name);
    if (!this.result?.success || !this.result.result) {
      // If no evaluation result yet, check statically from expression
      this.missingRequired = requiredFields;
      return;
    }
    const producedKeys = new Set(Object.keys(this.result.result));
    this.missingRequired = requiredFields.filter((f) => !producedKeys.has(f));
  }
}
