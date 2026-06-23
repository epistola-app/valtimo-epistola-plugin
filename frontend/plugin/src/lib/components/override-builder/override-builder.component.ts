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
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FormioCustomComponent } from '@valtimo/components';
import { Subject, of } from 'rxjs';
import { catchError, takeUntil } from 'rxjs/operators';
import { JsonataEditorComponent } from '../jsonata-editor/jsonata-editor.component';
import { OverrideRow, parseOverrideJsonata, serializeOverrideRows } from './override-jsonata';
import { isLegacyOverrideMapping, legacyOverrideToJsonata } from './legacy-override-converter';
import { EpistolaPluginService } from '../../services';
import { extractReferencedPaths, ReferencedPath } from '../../utils/extract-referenced-paths';

export interface FormFieldOption {
  key: string;
  label: string;
}

/**
 * Override mapping value: a JSONata expression (over `$form`) that produces the
 * `{ doc, pv }` overlay applied during preview. Legacy form definitions may
 * still carry the old `{ scope: { inputPath: "form:key" } }` object; it is
 * converted to JSONata on load (see {@link legacyOverrideToJsonata}).
 */
export type OverrideMapping = Record<string, Record<string, string>>;

@Component({
  standalone: true,
  imports: [CommonModule, FormsModule, JsonataEditorComponent],
  selector: 'epistola-override-builder-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="override-builder" data-testid="epistola-override-root">
      <div class="builder-header">
        <span class="builder-label" data-testid="epistola-override-label">{{
          label || 'Input Overrides'
        }}</span>
        <button
          type="button"
          class="mode-toggle"
          data-testid="epistola-override-mode-toggle"
          [disabled]="simpleUnavailable && !advancedMode"
          (click)="toggleMode()"
        >
          {{ advancedMode ? 'Simple' : 'Advanced' }}
        </button>
      </div>

      <!-- Inline guidance for the author -->
      <p class="builder-intro">
        Make the preview reflect what the user is typing — <em>before</em> they submit — by feeding
        live form values into the document inputs.
      </p>
      <details class="builder-help" data-testid="epistola-override-help">
        <summary data-testid="epistola-override-help-summary">When should I map a field?</summary>
        <ul>
          <li>
            <strong>Map</strong> a field when its value ends up in the generated document — i.e. the
            template's data mapping reads that <code>doc</code>/<code>pv</code> path. The preview
            then updates live as the field is filled in.
          </li>
          <li>
            <strong>Don't map</strong> fields that don't affect the document, or values that are
            already saved on the case/process before this task — those are read from the real data
            automatically.
          </li>
          <li>
            Overriding a path the template never reads has <strong>no effect</strong> on the
            preview.
          </li>
        </ul>
        <p class="builder-help__how">
          <strong>How it works:</strong> <code>$form</code> holds the current form values; the
          mapping returns a <code>{{ '{' }} doc, pv {{ '}' }}</code> overlay used
          <strong>only for the preview</strong>. The actual document is always generated from the
          real saved data after the form is submitted.
        </p>
      </details>

      <!-- Variables the selected template's mapping consumes (read-only guidance) -->
      <details
        *ngIf="hasReferencedPaths"
        class="used-by-template"
        data-testid="epistola-override-used-by-template"
      >
        <summary
          class="used-by-template__label"
          data-testid="epistola-override-used-by-template-summary"
        >
          Used by this template ({{ referencedPaths.length }})
        </summary>
        <p class="used-by-template__hint">
          This template's data mapping reads these inputs — the paths worth overriding for the
          preview.
        </p>
        <ul class="used-by-template__list" data-testid="epistola-override-used-by-template-list">
          <li *ngFor="let ref of referencedPaths; let i = index">
            <code [attr.data-testid]="'epistola-override-used-by-template-item-' + i">{{
              formatReferencedPath(ref)
            }}</code>
          </li>
        </ul>
      </details>

      <!-- Per-scope autocomplete options for the Input Path column -->
      <datalist id="epistola-override-paths-doc">
        <option *ngFor="let p of referencedPathsForScope('doc')" [value]="p"></option>
      </datalist>
      <datalist id="epistola-override-paths-pv">
        <option *ngFor="let p of referencedPathsForScope('pv')" [value]="p"></option>
      </datalist>

      <!-- Simple mode: table -->
      <div *ngIf="!advancedMode" class="builder-table" data-testid="epistola-override-table">
        <div
          *ngIf="rows.length > 0"
          class="table-header"
          data-testid="epistola-override-table-header"
        >
          <span class="col-scope">Scope</span>
          <span class="col-path">Input Path</span>
          <span class="col-field">Form Field</span>
          <span class="col-action"></span>
        </div>
        <div
          *ngFor="let row of rows; let i = index"
          class="table-row"
          [attr.data-testid]="'epistola-override-row-' + i"
        >
          <select
            class="col-scope"
            [attr.data-testid]="'epistola-override-row-scope-' + i"
            [(ngModel)]="row.scope"
            (ngModelChange)="emitChange()"
          >
            <option value="doc">doc</option>
            <option value="pv">pv</option>
          </select>
          <input
            class="col-path"
            type="text"
            [attr.data-testid]="'epistola-override-row-path-' + i"
            [(ngModel)]="row.inputPath"
            (ngModelChange)="emitChange()"
            [attr.list]="'epistola-override-paths-' + row.scope"
            placeholder="e.g. beslissing.tekst"
          />
          <!-- Dropdown when form fields are available, text input as fallback -->
          <select
            *ngIf="availableFields.length > 0"
            class="col-field"
            [attr.data-testid]="'epistola-override-row-field-' + i"
            [(ngModel)]="row.formFieldKey"
            (ngModelChange)="emitChange()"
          >
            <option value="">-- Select field --</option>
            <option *ngFor="let field of availableFields" [value]="field.key">
              {{ field.label }}
            </option>
          </select>
          <input
            *ngIf="availableFields.length === 0"
            class="col-field"
            type="text"
            [attr.data-testid]="'epistola-override-row-field-' + i"
            [(ngModel)]="row.formFieldKey"
            (ngModelChange)="emitChange()"
            placeholder="form field key"
          />
          <button
            type="button"
            class="col-action remove-btn"
            [attr.data-testid]="'epistola-override-row-remove-' + i"
            (click)="removeRow(i)"
          >
            <i class="mdi mdi-close"></i>
          </button>
        </div>
        <button
          type="button"
          class="add-btn"
          data-testid="epistola-override-add"
          (click)="addRow()"
        >
          <i class="mdi mdi-plus mr-1"></i> Add override
        </button>
      </div>

      <!-- Advanced mode: JSONata editor over $form -->
      <div *ngIf="advancedMode" class="builder-advanced" data-testid="epistola-override-advanced">
        <div
          *ngIf="simpleUnavailable"
          class="advanced-note"
          data-testid="epistola-override-advanced-note"
        >
          This expression is too rich for the simple table — edit it here.
        </div>
        <epistola-jsonata-editor
          data-testid="epistola-override-jsonata-editor"
          [expression]="expression"
          [contextVariables]="editorContextVariables"
          variablesHint="$form"
          (expressionChange)="onExpressionChange($event)"
        ></epistola-jsonata-editor>
        <div class="advanced-hint">
          Map form fields onto a <code>{{ '{' }} doc, pv {{ '}' }}</code> overlay, e.g.
          <code>{{ exampleExpression }}</code>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .override-builder {
        border: 1px solid #dee2e6;
        border-radius: 4px;
        padding: 0.75rem;
        background: #f8f9fa;
      }
      .builder-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 0.5rem;
      }
      .builder-label {
        font-weight: 600;
        font-size: 0.85rem;
        color: #495057;
      }
      .builder-intro {
        font-size: 0.78rem;
        color: #495057;
        margin: 0 0 0.4rem;
        line-height: 1.4;
      }
      .builder-help {
        margin: 0 0 0.6rem;
        font-size: 0.76rem;
        color: #6c757d;
      }
      .builder-help > summary {
        cursor: pointer;
        color: #0d6efd;
        font-size: 0.76rem;
        user-select: none;
      }
      .builder-help ul {
        margin: 0.35rem 0 0.35rem 0;
        padding-left: 1.1rem;
        line-height: 1.45;
      }
      .builder-help li {
        margin-bottom: 0.2rem;
      }
      .builder-help__how {
        margin: 0.35rem 0 0;
        line-height: 1.45;
      }
      .builder-help code {
        background: #eef0f2;
        border-radius: 3px;
        padding: 0 0.2rem;
      }
      .used-by-template {
        border: 1px solid #d6e4ff;
        background: #f0f6ff;
        border-radius: 4px;
        padding: 0.5rem 0.6rem;
        margin: 0 0 0.6rem;
      }
      .used-by-template__label {
        font-weight: 600;
        font-size: 0.78rem;
        color: #495057;
        cursor: pointer;
        user-select: none;
      }
      .used-by-template[open] .used-by-template__label {
        margin-bottom: 0.1rem;
      }
      .used-by-template__hint {
        margin: 0.2rem 0 0.4rem;
        font-size: 0.74rem;
        color: #6c757d;
        line-height: 1.4;
      }
      .used-by-template__list {
        margin: 0;
        padding-left: 1.1rem;
        display: flex;
        flex-wrap: wrap;
        gap: 0.15rem 1rem;
        list-style: none;
      }
      .used-by-template__list code {
        background: #e2ecff;
        border-radius: 3px;
        padding: 0 0.25rem;
        font-size: 0.76rem;
        color: #0d4a9c;
      }
      .mode-toggle {
        background: none;
        border: 1px solid #6c757d;
        border-radius: 4px;
        color: #6c757d;
        padding: 0.15rem 0.5rem;
        font-size: 0.75rem;
        cursor: pointer;
      }
      .mode-toggle:hover:not(:disabled) {
        background: #e9ecef;
      }
      .mode-toggle:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
      .table-header {
        display: flex;
        gap: 0.5rem;
        padding: 0.25rem 0;
        font-size: 0.75rem;
        color: #6c757d;
        font-weight: 600;
      }
      .table-row {
        display: flex;
        gap: 0.5rem;
        margin-bottom: 0.25rem;
        align-items: center;
      }
      .col-scope {
        width: 70px;
        flex-shrink: 0;
      }
      .col-path {
        flex: 1;
        min-width: 0;
      }
      .col-field {
        flex: 1;
        min-width: 0;
      }
      .col-action {
        width: 30px;
        flex-shrink: 0;
      }
      .table-row select,
      .table-row input {
        border: 1px solid #ced4da;
        border-radius: 4px;
        padding: 0.25rem 0.4rem;
        font-size: 0.8rem;
        background: white;
      }
      .remove-btn {
        background: none;
        border: none;
        color: #dc3545;
        cursor: pointer;
        padding: 0.25rem;
        font-size: 0.9rem;
      }
      .remove-btn:hover {
        color: #a71d2a;
      }
      .add-btn {
        background: none;
        border: 1px dashed #6c757d;
        border-radius: 4px;
        color: #6c757d;
        padding: 0.25rem 0.75rem;
        font-size: 0.8rem;
        cursor: pointer;
        margin-top: 0.25rem;
        display: flex;
        align-items: center;
      }
      .add-btn:hover {
        background: #e9ecef;
        border-color: #495057;
      }
      .advanced-note {
        color: #b54708;
        font-size: 0.75rem;
        margin-bottom: 0.4rem;
      }
      .advanced-hint {
        color: #6c757d;
        font-size: 0.72rem;
        margin-top: 0.35rem;
        line-height: 1.4;
      }
      .advanced-hint code {
        background: #eef0f2;
        border-radius: 3px;
        padding: 0 0.2rem;
        font-size: 0.95em;
      }
    `,
  ],
})
export class EpistolaOverrideBuilderComponent
  implements FormioCustomComponent<string | OverrideMapping | null>, OnDestroy
{
  @Input() value!: string | OverrideMapping | null;
  @Output() valueChange = new EventEmitter<string | OverrideMapping | null>();

  @Input() disabled = false;
  @Input() label = 'Input Overrides';
  @Input() availableFields: FormFieldOption[] = [];
  /**
   * Identify the selected generate-document process link, forwarded from the
   * preview component's editForm. Used to fetch the link's data mapping and
   * surface which `$doc`/`$pv` paths it consumes — purely informational guidance.
   */
  @Input() processDefinitionKey = '';
  @Input() sourceActivityId = '';

  rows: OverrideRow[] = [];
  advancedMode = false;
  /** True when the current expression can't be represented by the simple table. */
  simpleUnavailable = false;
  expression = '';

  /** `$doc`/`$pv`/`$case` paths the selected template's data mapping references. */
  referencedPaths: ReferencedPath[] = [];

  readonly exampleExpression = '{ "doc": { "naam": $form.voornaam & \' \' & $form.achternaam } }';

  private initialized = false;
  private readonly destroy$ = new Subject<void>();
  /** Link last fetched, so we refetch only when the selected process link changes. */
  private lastFetchedLinkKey: string | null = null;

  constructor(
    private readonly cdr: ChangeDetectorRef,
    private readonly pluginService: EpistolaPluginService,
  ) {}

  get formFieldKeys(): string[] {
    return this.availableFields.map((f) => f.key);
  }

  get hasReferencedPaths(): boolean {
    return this.referencedPaths.length > 0;
  }

  /** Referenced paths for a scope, excluding whole-scope refs (empty path) that aren't completions. */
  referencedPathsForScope(scope: ReferencedPath['scope']): string[] {
    return this.referencedPaths.filter((p) => p.scope === scope && p.path).map((p) => p.path);
  }

  /** Autocomplete context for the advanced editor: form fields plus the mapping's referenced paths. */
  get editorContextVariables(): Record<string, string[]> {
    return {
      form: this.formFieldKeys,
      doc: this.referencedPathsForScope('doc'),
      pv: this.referencedPathsForScope('pv'),
      case: this.referencedPathsForScope('case'),
    };
  }

  /** Render a referenced path as a `$scope.path` reference (or `$scope` for a whole-scope ref). */
  formatReferencedPath(ref: ReferencedPath): string {
    return ref.path ? `$${ref.scope}.${ref.path}` : `$${ref.scope}`;
  }

  ngOnChanges(_changes?: SimpleChanges): void {
    if (!this.initialized && this.value != null) {
      this.initialized = true;

      // Migrate a legacy object value to JSONata once, and persist it upward so
      // the form is saved in the new format. Everything below works on a string.
      if (isLegacyOverrideMapping(this.value)) {
        this.expression = legacyOverrideToJsonata(this.value);
        this.value = this.expression || null;
        this.valueChange.emit(this.value as string | null);
      } else {
        this.expression = String(this.value);
      }

      this.loadFromExpression(this.expression);
    }
    this.refreshReferencedPaths();
    this.cdr.markForCheck();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Fetch the selected process link's data mapping and extract the `$doc`/`$pv`/`$case`
   * paths it references, so the author sees what this template consumes. Refetches only
   * when the selected link changes; clears when no link is selected. Best-effort and
   * non-blocking — a failed fetch simply shows no suggestions.
   */
  private refreshReferencedPaths(): void {
    const linkKey =
      this.processDefinitionKey && this.sourceActivityId
        ? `${this.processDefinitionKey}::${this.sourceActivityId}`
        : null;

    if (!linkKey) {
      if (this.lastFetchedLinkKey !== null) {
        this.lastFetchedLinkKey = null;
        this.referencedPaths = [];
      }
      return;
    }
    if (linkKey === this.lastFetchedLinkKey) {
      return;
    }
    this.lastFetchedLinkKey = linkKey;

    this.pluginService
      .getProcessLinkMapping(this.processDefinitionKey, this.sourceActivityId)
      .pipe(
        takeUntil(this.destroy$),
        catchError(() => of({ dataMapping: '' })),
      )
      .subscribe((mapping) => {
        this.referencedPaths = extractReferencedPaths(mapping.dataMapping);
        this.cdr.markForCheck();
      });
  }

  toggleMode(): void {
    if (this.advancedMode) {
      // Advanced -> simple: only possible when the expression round-trips.
      const parsed = parseOverrideJsonata(this.expression);
      if (parsed === null) {
        this.simpleUnavailable = true;
        return;
      }
      this.rows = parsed;
      this.simpleUnavailable = false;
      this.advancedMode = false;
    } else {
      this.advancedMode = true;
    }
  }

  addRow(): void {
    this.rows.push({ scope: 'doc', inputPath: '', formFieldKey: '' });
  }

  removeRow(index: number): void {
    this.rows.splice(index, 1);
    this.emitChange();
  }

  /** Simple-table change: serialize rows back to a JSONata expression. */
  emitChange(): void {
    this.expression = serializeOverrideRows(this.rows);
    this.emit(this.expression);
  }

  /** Advanced-editor change. */
  onExpressionChange(expr: string): void {
    this.expression = expr;
    this.simpleUnavailable = parseOverrideJsonata(expr) === null;
    this.emit(expr);
  }

  private loadFromExpression(expression: string): void {
    const parsed = parseOverrideJsonata(expression);
    if (parsed === null) {
      // Richer than the simple table can show — start in advanced mode.
      this.simpleUnavailable = true;
      this.advancedMode = true;
      this.rows = [];
    } else {
      this.simpleUnavailable = false;
      this.rows = parsed;
    }
  }

  private emit(expression: string): void {
    const next = expression && expression.trim() ? expression : null;
    this.value = next;
    this.valueChange.emit(next);
  }
}
