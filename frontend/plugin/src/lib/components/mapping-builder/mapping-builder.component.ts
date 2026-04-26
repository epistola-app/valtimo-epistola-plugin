import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { TemplateField, VariableSuggestions } from '../../models';
import {
  BuilderField,
  builderToJsonata,
  parseJsonataToBuilder,
} from '../../utils/jsonata-converter';

@Component({
  selector: 'epistola-mapping-builder',
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule],
  template: `
    <div class="mapping-builder">
      <div
        *ngIf="fields.length === 0 && (!templateFields || templateFields.length === 0)"
        class="mapping-builder__empty"
      >
        {{ 'noTemplateFields' | pluginTranslate: 'epistola' | async }}
      </div>

      <div *ngFor="let field of fields; let i = index" class="mapping-builder__row">
        <div class="mapping-builder__name">
          <span class="mapping-builder__field-name">{{ field.name }}</span>
          <span *ngIf="isRequired(field.name)" class="mapping-builder__required">*</span>
          <span *ngIf="field.children" class="mapping-builder__type">(object)</span>
        </div>

        <div class="mapping-builder__value" *ngIf="!field.children">
          <input
            *ngIf="field.mode === 'ref'"
            type="text"
            class="mapping-builder__input"
            [ngModel]="field.value"
            (ngModelChange)="onFieldValueChange(i, $event)"
            [disabled]="disabled"
            placeholder="doc:path.to.field"
            [attr.list]="'suggestions-' + i"
          />
          <datalist *ngIf="field.mode === 'ref'" [id]="'suggestions-' + i">
            <option *ngFor="let s of allSuggestions" [value]="s"></option>
          </datalist>
          <input
            *ngIf="field.mode === 'raw'"
            type="text"
            class="mapping-builder__input mapping-builder__input--raw"
            [ngModel]="field.value"
            (ngModelChange)="onFieldValueChange(i, $event)"
            [disabled]="disabled"
            placeholder="JSONata expression"
          />
          <button
            class="mapping-builder__mode-toggle"
            (click)="toggleFieldMode(i)"
            [disabled]="disabled"
            [title]="field.mode === 'ref' ? 'Switch to raw JSONata' : 'Switch to reference'"
          >
            {{ field.mode === 'ref' ? 'fx' : '·' }}
          </button>
        </div>

        <!-- Nested object children -->
        <div *ngIf="field.children" class="mapping-builder__children">
          <div
            *ngFor="let child of field.children; let j = index"
            class="mapping-builder__row mapping-builder__row--child"
          >
            <div class="mapping-builder__name">
              <span class="mapping-builder__field-name">{{ child.name }}</span>
            </div>
            <div class="mapping-builder__value">
              <input
                *ngIf="child.mode === 'ref'"
                type="text"
                class="mapping-builder__input"
                [ngModel]="child.value"
                (ngModelChange)="onChildValueChange(i, j, $event)"
                [disabled]="disabled"
                placeholder="doc:path.to.field"
                [attr.list]="'suggestions-' + i + '-' + j"
              />
              <datalist *ngIf="child.mode === 'ref'" [id]="'suggestions-' + i + '-' + j">
                <option *ngFor="let s of allSuggestions" [value]="s"></option>
              </datalist>
              <input
                *ngIf="child.mode === 'raw'"
                type="text"
                class="mapping-builder__input mapping-builder__input--raw"
                [ngModel]="child.value"
                (ngModelChange)="onChildValueChange(i, j, $event)"
                [disabled]="disabled"
                placeholder="JSONata expression"
              />
              <button
                class="mapping-builder__mode-toggle"
                (click)="toggleChildMode(i, j)"
                [disabled]="disabled"
                [title]="child.mode === 'ref' ? 'Switch to raw JSONata' : 'Switch to reference'"
              >
                {{ child.mode === 'ref' ? 'fx' : '·' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .mapping-builder__empty {
        color: #6f6f6f;
        font-size: 0.9em;
        padding: 12px 0;
      }
      .mapping-builder__row {
        margin-bottom: 8px;
      }
      .mapping-builder__row--child {
        margin-left: 20px;
        margin-bottom: 4px;
      }
      .mapping-builder__name {
        margin-bottom: 2px;
      }
      .mapping-builder__field-name {
        font-weight: 500;
        font-size: 0.9em;
      }
      .mapping-builder__required {
        color: #da1e28;
        margin-left: 2px;
      }
      .mapping-builder__type {
        color: #8d8d8d;
        font-size: 0.8em;
        margin-left: 4px;
      }
      .mapping-builder__value {
        display: flex;
        align-items: center;
        gap: 4px;
      }
      .mapping-builder__input {
        flex: 1;
        padding: 6px 8px;
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        font-size: 0.85em;
        font-family: 'IBM Plex Mono', monospace;
      }
      .mapping-builder__input:focus {
        outline: 2px solid #0f62fe;
        border-color: #0f62fe;
      }
      .mapping-builder__input--raw {
        background: #f4f4f4;
      }
      .mapping-builder__mode-toggle {
        width: 28px;
        height: 28px;
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        background: #fff;
        cursor: pointer;
        font-family: monospace;
        font-size: 0.8em;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .mapping-builder__mode-toggle:hover {
        background: #f4f4f4;
      }
      .mapping-builder__children {
        border-left: 2px solid #e0e0e0;
        padding-left: 12px;
        margin-top: 4px;
      }
    `,
  ],
})
export class MappingBuilderComponent implements OnChanges {
  @Input() expression: string = '';
  @Input() templateFields: TemplateField[] = [];
  @Input() suggestions: VariableSuggestions | null = null;
  @Input() disabled: boolean = false;
  @Output() expressionChange = new EventEmitter<string>();

  fields: BuilderField[] = [];
  allSuggestions: string[] = [];

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['expression'] && !changes['expression'].firstChange) {
      return; // Don't re-parse when we emit changes ourselves
    }
    if (changes['expression'] || changes['templateFields']) {
      this.rebuildFields();
    }
    if (changes['suggestions']) {
      this.buildSuggestionList();
    }
  }

  onFieldValueChange(index: number, value: string): void {
    this.fields[index] = { ...this.fields[index], value };
    this.emit();
  }

  onChildValueChange(parentIndex: number, childIndex: number, value: string): void {
    const parent = this.fields[parentIndex];
    if (parent.children) {
      parent.children[childIndex] = { ...parent.children[childIndex], value };
      this.fields[parentIndex] = { ...parent };
      this.emit();
    }
  }

  isRequired(fieldName: string): boolean {
    return this.templateFields?.find((tf) => tf.name === fieldName)?.required ?? false;
  }

  toggleFieldMode(index: number): void {
    const field = this.fields[index];
    this.fields[index] = {
      ...field,
      mode: field.mode === 'ref' ? 'raw' : 'ref',
    };
    this.emit();
  }

  toggleChildMode(parentIndex: number, childIndex: number): void {
    const parent = this.fields[parentIndex];
    if (parent.children) {
      const child = parent.children[childIndex];
      parent.children[childIndex] = {
        ...child,
        mode: child.mode === 'ref' ? 'raw' : 'ref',
      };
      this.fields[parentIndex] = { ...parent };
      this.emit();
    }
  }

  private emit(): void {
    const jsonata = builderToJsonata(this.fields);
    this.expressionChange.emit(jsonata);
  }

  /**
   * Ensure all template fields have a corresponding builder field.
   * Adds missing fields with empty values.
   */
  private buildSuggestionList(): void {
    if (!this.suggestions) {
      this.allSuggestions = [];
      return;
    }
    const docSuggestions = (this.suggestions.doc || []).map((p) => `doc:${p}`);
    const pvSuggestions = (this.suggestions.pv || []).map((p) => `pv:${p}`);
    this.allSuggestions = [...docSuggestions, ...pvSuggestions];
  }

  /**
   * Rebuild fields using template fields as the source of truth.
   * Expression values fill in where available; unmapped fields show empty.
   */
  private rebuildFields(): void {
    const parsed = parseJsonataToBuilder(this.expression);
    const parsedByName = new Map(parsed.map((f) => [f.name, f]));

    if (!this.templateFields || this.templateFields.length === 0) {
      // No template fields yet — use whatever we parsed
      this.fields = parsed;
      return;
    }

    // Template fields drive the structure
    this.fields = this.templateFields.map((tf) => {
      const existing = parsedByName.get(tf.name);
      if (existing) {
        return existing;
      }
      if (tf.fieldType === 'OBJECT' && tf.children?.length) {
        return {
          name: tf.name,
          mode: 'ref' as const,
          value: '',
          children: tf.children.map((c) => ({ name: c.name, mode: 'ref' as const, value: '' })),
        };
      }
      return { name: tf.name, mode: 'ref' as const, value: '' };
    });

    // Include extra fields from expression not in the template schema
    for (const p of parsed) {
      if (!this.templateFields.find((tf) => tf.name === p.name)) {
        this.fields.push(p);
      }
    }
  }
}
