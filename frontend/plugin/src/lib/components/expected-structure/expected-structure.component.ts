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

import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { JsonSchema, TemplateField } from '../../models';
import {
  buildResolvedTemplateStructure,
  serializeTemplateSchema,
} from '../../schema/template-schema';

@Component({
  selector: 'epistola-expected-structure',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule],
  template: `
    <div class="expected" data-testid="epistola-schema-panel">
      <div class="expected__header" data-testid="epistola-schema-header">
        <span>{{ 'expectedStructure' | pluginTranslate: 'epistola' | async }}</span>
        <div *ngIf="schema !== null && schema !== undefined" class="expected__modes">
          <button
            type="button"
            class="expected__mode"
            [class.expected__mode--active]="viewMode === 'resolved'"
            data-testid="epistola-schema-mode-resolved"
            (click)="viewMode = 'resolved'"
          >
            {{ 'resolvedSchema' | pluginTranslate: 'epistola' | async }}
          </button>
          <button
            type="button"
            class="expected__mode"
            [class.expected__mode--active]="viewMode === 'raw'"
            data-testid="epistola-schema-mode-raw"
            (click)="viewMode = 'raw'"
          >
            {{ 'rawSchema' | pluginTranslate: 'epistola' | async }}
          </button>
        </div>
      </div>
      <div
        *ngIf="viewMode === 'resolved' && (!templateFields || templateFields.length === 0)"
        class="expected__empty"
        data-testid="epistola-schema-empty"
      >
        {{ 'expectedStructureLoading' | pluginTranslate: 'epistola' | async }}
      </div>
      <pre
        *ngIf="viewMode === 'resolved' && templateFields && templateFields.length > 0"
        class="expected__code"
        data-testid="epistola-schema-code"
        >{{ structureText }}</pre
      >
      <pre
        *ngIf="viewMode === 'raw'"
        class="expected__code"
        data-testid="epistola-schema-raw-code"
        >{{ rawSchemaText }}</pre
      >
    </div>
  `,
  styles: [
    `
      .expected {
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        overflow: hidden;
        height: 100%;
        display: flex;
        flex-direction: column;
      }
      .expected__header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        padding: 6px 12px;
        background: #f4f4f4;
        border-bottom: 1px solid #e0e0e0;
        font-size: 0.75em;
        color: #6f6f6f;
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }
      .expected__modes {
        display: flex;
        gap: 2px;
      }
      .expected__mode {
        border: 0;
        border-bottom: 2px solid transparent;
        background: transparent;
        color: #525252;
        cursor: pointer;
        font: inherit;
        padding: 2px 4px;
        text-transform: none;
      }
      .expected__mode--active {
        border-bottom-color: #0f62fe;
        color: #0f62fe;
      }
      .expected__code {
        flex: 1;
        font-family: 'IBM Plex Mono', monospace;
        font-size: 0.8em;
        line-height: 1.5;
        margin: 0;
        padding: 8px 12px;
        white-space: pre-wrap;
        overflow-y: auto;
      }
      .expected__empty {
        padding: 8px 12px;
        color: #8d8d8d;
        font-size: 0.85em;
        font-style: italic;
      }
    `,
  ],
})
export class ExpectedStructureComponent implements OnChanges {
  @Input() templateFields: TemplateField[] = [];
  @Input() schema: JsonSchema | boolean | null = null;

  structureText: string = '{}';
  rawSchemaText: string = '{}';
  viewMode: 'resolved' | 'raw' = 'resolved';

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['templateFields']) {
      this.structureText = buildResolvedTemplateStructure(this.templateFields);
    }
    if (changes['schema']) {
      this.rawSchemaText = serializeTemplateSchema(this.schema);
    }
  }
}
