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

import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { TemplateField, VariableSuggestions } from '../../models';
import { BuilderFieldComponent } from './builder-field/builder-field.component';
import {
  BuilderField,
  builderToJsonata,
  parseJsonataToBuilder,
} from '../../utils/jsonata-converter';

@Component({
  selector: 'epistola-mapping-builder',
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule, BuilderFieldComponent],
  template: `
    <div class="mapping-builder">
      <div
        *ngIf="fields.length === 0 && (!templateFields || templateFields.length === 0)"
        class="mapping-builder__empty"
      >
        {{ 'noTemplateFields' | pluginTranslate: 'epistola' | async }}
      </div>

      <epistola-builder-field
        *ngFor="let field of fields; let i = index"
        [field]="field"
        [path]="[i]"
        [suggestions]="allSuggestions"
        [disabled]="disabled"
        [collapsed]="isCollapsed([i])"
        [collapsedPaths]="collapsedPaths"
        [required]="isRequired(field.name)"
        (valueChange)="onNestedValueChange($event.path, $event.value)"
        (modeToggle)="onNestedModeToggle($event)"
        (collapseToggle)="toggleCollapse($event)"
      ></epistola-builder-field>
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
      .mapping-builder__name--clickable {
        cursor: pointer;
        user-select: none;
      }
      .mapping-builder__name--clickable:hover {
        color: #0f62fe;
      }
      .mapping-builder__chevron {
        font-size: 0.7em;
        margin-right: 4px;
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
  collapsedPaths = new Set<string>();
  private initialCollapseApplied = false;

  ngOnChanges(changes: SimpleChanges): void {
    // Skip re-parse only when expression alone changed (from our own emit)
    const expressionChanged = !!changes['expression'];
    const templateFieldsChanged = !!changes['templateFields'];

    if (expressionChanged && !templateFieldsChanged && !changes['expression'].firstChange) {
      return; // Don't re-parse when we emit changes ourselves
    }
    if (expressionChanged || templateFieldsChanged) {
      this.rebuildFields();
      if (!this.initialCollapseApplied && this.fields.length > 0) {
        this.collapseAll();
        this.initialCollapseApplied = true;
      }
    }
    if (changes['suggestions']) {
      this.buildSuggestionList();
    }
  }

  onNestedValueChange(path: number[], value: string): void {
    const field = this.getFieldAtPath(path);
    if (field) {
      field.value = value;
      this.emit();
    }
  }

  onNestedModeToggle(path: number[]): void {
    const field = this.getFieldAtPath(path);
    if (field) {
      field.mode = field.mode === 'ref' ? 'raw' : 'ref';
      this.emit();
    }
  }

  isRequired(fieldName: string): boolean {
    return this.templateFields?.find((tf) => tf.name === fieldName)?.required ?? false;
  }

  isCollapsed(path: number[]): boolean {
    return this.collapsedPaths.has(path.join('.'));
  }

  toggleCollapse(path: number[]): void {
    const key = path.join('.');
    if (this.collapsedPaths.has(key)) {
      this.collapsedPaths.delete(key);
    } else {
      this.collapsedPaths.add(key);
    }
  }

  private collapseAll(): void {
    this.collapsedPaths.clear();
    this.fields.forEach((field, i) => {
      if (field.children) {
        this.collapsedPaths.add(String(i));
        this.collapseChildren(field.children, [i]);
      }
    });
  }

  private collapseChildren(children: BuilderField[], parentPath: number[]): void {
    children.forEach((child, j) => {
      if (child.children) {
        this.collapsedPaths.add([...parentPath, j].join('.'));
        this.collapseChildren(child.children, [...parentPath, j]);
      }
    });
  }

  private getFieldAtPath(path: number[]): BuilderField | null {
    if (path.length === 0) return null;
    let current: BuilderField = this.fields[path[0]];
    for (let i = 1; i < path.length; i++) {
      if (!current.children) return null;
      current = current.children[path[i]];
    }
    return current;
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
    const docSuggestions = (this.suggestions.doc || []).map((p) => `$doc.${p}`);
    const pvSuggestions = (this.suggestions.pv || []).map((p) => `$pv.${p}`);
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
