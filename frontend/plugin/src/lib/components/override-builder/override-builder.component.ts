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

interface OverrideRow {
  scope: 'doc' | 'pv';
  inputPath: string;
  formField: string;
}

export type OverrideMapping = Record<string, Record<string, string>>;

@Component({
  standalone: true,
  imports: [CommonModule, FormsModule],
  selector: 'epistola-override-builder-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="override-builder">
      <div class="builder-header">
        <span class="builder-label">{{ label || 'Input Overrides' }}</span>
        <button type="button" class="mode-toggle" (click)="toggleMode()">
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
          <input
            class="col-field"
            type="text"
            [(ngModel)]="row.formField"
            (ngModelChange)="emitChange()"
            placeholder="e.g. beslissing"
          />
          <button type="button" class="col-action remove-btn" (click)="removeRow(i)">
            <i class="mdi mdi-close"></i>
          </button>
        </div>
        <button type="button" class="add-btn" (click)="addRow()">
          <i class="mdi mdi-plus mr-1"></i> Add override
        </button>
      </div>

      <!-- Advanced mode: JSON editor -->
      <div *ngIf="advancedMode" class="builder-advanced">
        <textarea
          class="json-editor"
          [ngModel]="jsonText"
          (ngModelChange)="onJsonChange($event)"
          placeholder='{ "doc": { "path": "formFieldKey" } }'
          rows="6"
        ></textarea>
        <div *ngIf="jsonError" class="json-error">{{ jsonError }}</div>
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
      .mode-toggle:hover {
        background: #e9ecef;
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
      .json-editor {
        width: 100%;
        border: 1px solid #ced4da;
        border-radius: 4px;
        padding: 0.5rem;
        font-family: monospace;
        font-size: 0.8rem;
        resize: vertical;
        background: white;
      }
      .json-error {
        color: #dc3545;
        font-size: 0.75rem;
        margin-top: 0.25rem;
      }
    `,
  ],
})
export class EpistolaOverrideBuilderComponent implements FormioCustomComponent<OverrideMapping | null> {
  @Input() value!: OverrideMapping | null;
  @Output() valueChange = new EventEmitter<OverrideMapping | null>();

  @Input() disabled = false;
  @Input() label = 'Input Overrides';

  rows: OverrideRow[] = [];
  advancedMode = false;
  jsonText = '';
  jsonError: string | null = null;

  private initialized = false;

  ngOnChanges(): void {
    if (!this.initialized && this.value) {
      this.initialized = true;
      this.rows = this.mappingToRows(this.value);
      this.jsonText = JSON.stringify(this.value, null, 2);
    }
  }

  toggleMode(): void {
    this.advancedMode = !this.advancedMode;
    if (this.advancedMode) {
      const mapping = this.rowsToMapping();
      this.jsonText = Object.keys(mapping).length > 0 ? JSON.stringify(mapping, null, 2) : '';
      this.jsonError = null;
    } else {
      try {
        const parsed = this.jsonText.trim() ? JSON.parse(this.jsonText) : {};
        this.rows = this.mappingToRows(parsed);
        this.jsonError = null;
      } catch {
        // Keep current rows if JSON is invalid
      }
    }
  }

  addRow(): void {
    this.rows.push({ scope: 'doc', inputPath: '', formField: '' });
  }

  removeRow(index: number): void {
    this.rows.splice(index, 1);
    this.emitChange();
  }

  emitChange(): void {
    const mapping = this.rowsToMapping();
    this.value = Object.keys(mapping).length > 0 ? mapping : null;
    this.valueChange.emit(this.value);
  }

  onJsonChange(text: string): void {
    this.jsonText = text;
    if (!text.trim()) {
      this.jsonError = null;
      this.value = null;
      this.valueChange.emit(null);
      return;
    }
    try {
      const parsed = JSON.parse(text);
      this.jsonError = null;
      this.value = parsed;
      this.valueChange.emit(parsed);
    } catch (e) {
      this.jsonError = 'Invalid JSON';
    }
  }

  private rowsToMapping(): OverrideMapping {
    const mapping: OverrideMapping = {};
    for (const row of this.rows) {
      if (row.inputPath && row.formField) {
        if (!mapping[row.scope]) {
          mapping[row.scope] = {};
        }
        mapping[row.scope][row.inputPath] = row.formField;
      }
    }
    return mapping;
  }

  private mappingToRows(mapping: OverrideMapping): OverrideRow[] {
    const rows: OverrideRow[] = [];
    for (const [scope, fields] of Object.entries(mapping)) {
      if (scope === 'doc' || scope === 'pv') {
        for (const [path, formField] of Object.entries(fields)) {
          rows.push({ scope, inputPath: path, formField: String(formField) });
        }
      }
    }
    return rows;
  }
}
