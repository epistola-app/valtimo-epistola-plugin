import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FormioCustomComponent } from '@valtimo/components';
import { JsonataEditorComponent } from '../jsonata-editor/jsonata-editor.component';
import { OverrideRow, parseOverrideJsonata, serializeOverrideRows } from './override-jsonata';
import { isLegacyOverrideMapping, legacyOverrideToJsonata } from './legacy-override-converter';

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
    <div class="override-builder">
      <div class="builder-header">
        <span class="builder-label">{{ label || 'Input Overrides' }}</span>
        <button
          type="button"
          class="mode-toggle"
          [disabled]="simpleUnavailable && !advancedMode"
          (click)="toggleMode()"
        >
          {{ advancedMode ? 'Simple' : 'Advanced' }}
        </button>
      </div>

      <!-- Simple mode: table -->
      <div *ngIf="!advancedMode" class="builder-table">
        <div *ngIf="rows.length > 0" class="table-header">
          <span class="col-scope">Scope</span>
          <span class="col-path">Input Path</span>
          <span class="col-field">Form Field</span>
          <span class="col-action"></span>
        </div>
        <div *ngFor="let row of rows; let i = index" class="table-row">
          <select class="col-scope" [(ngModel)]="row.scope" (ngModelChange)="emitChange()">
            <option value="doc">doc</option>
            <option value="pv">pv</option>
          </select>
          <input
            class="col-path"
            type="text"
            [(ngModel)]="row.inputPath"
            (ngModelChange)="emitChange()"
            placeholder="e.g. beslissing.tekst"
          />
          <!-- Dropdown when form fields are available, text input as fallback -->
          <select
            *ngIf="availableFields.length > 0"
            class="col-field"
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
            [(ngModel)]="row.formFieldKey"
            (ngModelChange)="emitChange()"
            placeholder="form field key"
          />
          <button type="button" class="col-action remove-btn" (click)="removeRow(i)">
            <i class="mdi mdi-close"></i>
          </button>
        </div>
        <button type="button" class="add-btn" (click)="addRow()">
          <i class="mdi mdi-plus mr-1"></i> Add override
        </button>
      </div>

      <!-- Advanced mode: JSONata editor over $form -->
      <div *ngIf="advancedMode" class="builder-advanced">
        <div *ngIf="simpleUnavailable" class="advanced-note">
          This expression is too rich for the simple table — edit it here.
        </div>
        <epistola-jsonata-editor
          [expression]="expression"
          [contextVariables]="{ form: formFieldKeys }"
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
export class EpistolaOverrideBuilderComponent implements FormioCustomComponent<
  string | OverrideMapping | null
> {
  @Input() value!: string | OverrideMapping | null;
  @Output() valueChange = new EventEmitter<string | OverrideMapping | null>();

  @Input() disabled = false;
  @Input() label = 'Input Overrides';
  @Input() availableFields: FormFieldOption[] = [];

  rows: OverrideRow[] = [];
  advancedMode = false;
  /** True when the current expression can't be represented by the simple table. */
  simpleUnavailable = false;
  expression = '';

  readonly exampleExpression = '{ "doc": { "naam": $form.voornaam & \' \' & $form.achternaam } }';

  private initialized = false;

  constructor(private readonly cdr: ChangeDetectorRef) {}

  get formFieldKeys(): string[] {
    return this.availableFields.map((f) => f.key);
  }

  ngOnChanges(): void {
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
    this.cdr.markForCheck();
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
