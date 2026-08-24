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

import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { BuilderField } from '../../../utils/jsonata-converter';
import { SmartExpressionEditorComponent } from '../../smart-expression-editor/smart-expression-editor.component';
import { ExpressionFunctionInfo } from '../../../models';

@Component({
  selector: 'epistola-builder-field',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, SmartExpressionEditorComponent],
  template: `
    <div class="builder-field" [attr.data-testid]="'epistola-mapping-field-' + field.name">
      <div
        class="builder-field__name"
        [class.builder-field__name--clickable]="field.children"
        [class.builder-field__name--object]="field.children"
        (click)="field.children && collapseToggle.emit(path)"
        [attr.data-testid]="'epistola-mapping-field-name-' + field.name"
      >
        <span
          *ngIf="field.children"
          class="builder-field__chevron"
          [attr.data-testid]="'epistola-mapping-field-chevron-' + field.name"
          >{{ collapsed ? '&#x25B6;' : '&#x25BC;' }}</span
        >
        <span class="builder-field__label" [attr.data-testid]="'epistola-mapping-field-label-' + field.name">{{
          field.name
        }}</span>
        <span
          *ngIf="required"
          class="builder-field__required"
          [attr.data-testid]="'epistola-mapping-field-required-' + field.name"
          >*</span
        >
        <span
          *ngIf="field.type"
          class="builder-field__type"
          [attr.data-testid]="'epistola-mapping-field-type-' + field.name"
          >({{ field.type }}{{ field.nullable ? ' | null' : '' }})</span
        >
        <span
          *ngIf="field.complex"
          class="builder-field__complex-badge"
          [attr.data-testid]="'epistola-mapping-field-complex-' + field.name"
        >
          {{ 'complexMappingField' | pluginTranslate: 'epistola' | async }}
        </span>
      </div>

      <div
        class="builder-field__value"
        *ngIf="!field.children"
        [attr.data-testid]="'epistola-mapping-field-value-' + field.name"
      >
        <epistola-smart-expression-editor
          class="builder-field__editor"
          [expression]="field.value"
          [contextVariables]="contextVariables"
          [functions]="functions"
          [disabled]="disabled"
          [required]="required"
          [compact]="true"
          [testId]="'epistola-mapping-field-input-' + field.name"
          (expressionChange)="valueChange.emit({ path: path, value: $event })"
          (validChange)="validityChange.emit({ path: path, valid: $event })"
        ></epistola-smart-expression-editor>
        <div
          *ngIf="field.complex"
          class="builder-field__complex-help"
          [attr.data-testid]="'epistola-mapping-field-complex-help-' + field.name"
        >
          {{ 'complexMappingFieldHelp' | pluginTranslate: 'epistola' | async }}
        </div>
      </div>

      <div
        *ngIf="field.children && !collapsed"
        class="builder-field__children"
        [attr.data-testid]="'epistola-mapping-field-children-' + field.name"
      >
        <epistola-builder-field
          *ngFor="let child of field.children; let j = index"
          [field]="child"
          [path]="path.concat(j)"
          [disabled]="disabled"
          [collapsed]="isChildCollapsed(j)"
          [collapsedPaths]="collapsedPaths"
          [required]="child.required ?? false"
          [contextVariables]="contextVariables"
          [functions]="functions"
          (valueChange)="valueChange.emit($event)"
          (validityChange)="validityChange.emit($event)"
          (collapseToggle)="collapseToggle.emit($event)"
        ></epistola-builder-field>
      </div>
    </div>
  `,
  styles: [
    `
      .builder-field {
        display: grid;
        grid-template-columns: minmax(8rem, 12rem) minmax(0, 1fr);
        column-gap: 12px;
        align-items: start;
        margin-bottom: 6px;
      }
      .builder-field__name {
        min-width: 0;
        padding-top: 0.4rem;
      }
      .builder-field__name--clickable {
        cursor: pointer;
        user-select: none;
      }
      .builder-field__name--clickable:hover {
        color: #0f62fe;
      }
      .builder-field__chevron {
        font-size: 0.7em;
        margin-right: 4px;
      }
      .builder-field__label {
        font-weight: 600;
        font-size: 0.875rem;
        overflow-wrap: anywhere;
      }
      .builder-field__required {
        color: #da1e28;
        margin-left: 2px;
      }
      .builder-field__type {
        color: #8d8d8d;
        font-size: 0.8em;
        margin-left: 4px;
      }
      .builder-field__value {
        display: grid;
        gap: 4px;
        min-width: 0;
      }
      .builder-field__editor {
        min-width: 0;
      }
      .builder-field__complex-badge {
        display: inline-block;
        margin-left: 6px;
        padding: 1px 6px;
        border-radius: 10px;
        background: #fff1f1;
        color: #a2191f;
        font-size: 0.72rem;
        font-weight: 600;
      }
      .builder-field__complex-help {
        color: #6f6f6f;
        font-size: 0.75rem;
        line-height: 1.35;
      }
      .builder-field__children {
        grid-column: 1 / -1;
        border-left: 2px solid #e0e0e0;
        padding-left: 12px;
        margin-top: 6px;
      }
      .builder-field__name--object {
        grid-column: 1 / -1;
        padding-top: 0.25rem;
      }
      @media (max-width: 48rem) {
        .builder-field {
          grid-template-columns: 1fr;
        }
        .builder-field__name {
          padding-top: 0;
          margin-bottom: 2px;
        }
        .builder-field__children {
          grid-column: 1;
        }
      }
    `,
  ],
})
export class BuilderFieldComponent {
  @Input() field!: BuilderField;
  @Input() path: number[] = [];
  @Input() disabled = false;
  @Input() collapsed = false;
  @Input() required = false;
  @Input() contextVariables: Record<string, string[]> = { doc: [], pv: [], case: [] };
  @Input() functions: ExpressionFunctionInfo[] = [];
  @Input() collapsedPaths: Set<string> = new Set();
  @Output() valueChange = new EventEmitter<{ path: number[]; value: string }>();
  @Output() validityChange = new EventEmitter<{ path: number[]; valid: boolean }>();
  @Output() collapseToggle = new EventEmitter<number[]>();

  isChildCollapsed(childIndex: number): boolean {
    return this.collapsedPaths.has(this.path.concat(childIndex).join('.'));
  }
}
