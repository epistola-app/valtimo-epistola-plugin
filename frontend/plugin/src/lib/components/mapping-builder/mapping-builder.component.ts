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
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { ExpressionFunctionInfo, TemplateField } from '../../models';
import { BuilderFieldComponent } from './builder-field/builder-field.component';
import {
  BuilderField,
  builderToJsonata,
  parseJsonataToBuilder,
} from '../../utils/jsonata-converter';

@Component({
  selector: 'epistola-mapping-builder',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, BuilderFieldComponent],
  template: `
    <div class="mapping-builder" data-testid="epistola-mapping-builder">
      <div
        *ngIf="fields.length === 0 && (!templateFields || templateFields.length === 0)"
        class="mapping-builder__empty"
        data-testid="epistola-mapping-empty"
      >
        {{ 'noTemplateFields' | pluginTranslate: 'epistola' | async }}
      </div>

      <epistola-builder-field
        *ngFor="let field of fields; let i = index"
        [attr.data-testid]="'epistola-mapping-row-' + field.name"
        [field]="field"
        [path]="[i]"
        [disabled]="disabled"
        [collapsed]="isCollapsed([i])"
        [collapsedPaths]="collapsedPaths"
        [required]="isRequired(field.name)"
        [contextVariables]="contextVariables"
        [functions]="functions"
        (valueChange)="onNestedValueChange($event.path, $event.value)"
        (validityChange)="onNestedValidityChange($event.path, $event.valid)"
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
  @Input() disabled: boolean = false;
  @Input() contextVariables: Record<string, string[]> = { doc: [], pv: [], case: [] };
  @Input() functions: ExpressionFunctionInfo[] = [];
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  fields: BuilderField[] = [];
  collapsedPaths = new Set<string>();
  private initialCollapseApplied = false;
  private readonly fieldValidity = new Map<string, boolean>();
  private lastEmittedExpression: string | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    const expressionChanged = !!changes['expression'];
    const templateFieldsChanged = !!changes['templateFields'];

    if (
      expressionChanged &&
      !templateFieldsChanged &&
      changes['expression'].currentValue === this.lastEmittedExpression
    ) {
      this.lastEmittedExpression = null;
      return;
    }
    if (expressionChanged || templateFieldsChanged) {
      this.fieldValidity.clear();
      this.rebuildFields();
      if (!this.initialCollapseApplied && this.fields.length > 0) {
        this.collapseAll();
        this.initialCollapseApplied = true;
      }
    }
  }

  onNestedValueChange(path: number[], value: string): void {
    const field = this.getFieldAtPath(path);
    if (field) {
      field.value = value;
      field.present = !!value.trim();
      this.emit();
    }
  }

  onNestedValidityChange(path: number[], valid: boolean): void {
    this.fieldValidity.set(path.join('.'), valid);
    this.validChange.emit([...this.fieldValidity.values()].every(Boolean));
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
    this.lastEmittedExpression = jsonata;
    this.expressionChange.emit(jsonata);
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
          present: false,
          children: tf.children.map((child) => this.emptyBuilderField(child)),
        };
      }
      return { name: tf.name, mode: 'ref' as const, value: '', present: false };
    });

    // Include extra fields from expression not in the template schema
    for (const p of parsed) {
      if (!this.templateFields.find((tf) => tf.name === p.name)) {
        this.fields.push(p);
      }
    }
  }

  private emptyBuilderField(templateField: TemplateField): BuilderField {
    if (templateField.fieldType === 'OBJECT' && templateField.children?.length) {
      return {
        name: templateField.name,
        mode: 'ref',
        value: '',
        present: false,
        children: templateField.children.map((child) => this.emptyBuilderField(child)),
      };
    }
    return {
      name: templateField.name,
      mode: 'ref',
      value: '',
      present: false,
    };
  }
}
