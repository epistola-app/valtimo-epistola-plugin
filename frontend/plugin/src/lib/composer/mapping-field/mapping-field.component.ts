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
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FormioCustomComponent } from '@valtimo/components';
import { PluginTranslatePipeModule, PluginTranslationService } from '@valtimo/plugin';
import * as _jsonata from 'jsonata';
import { extractReferencedPaths } from '../../utils/extract-referenced-paths';

const jsonata = (_jsonata as any).default || _jsonata;

/**
 * The baseline mapping field in a letter composer's settings.
 *
 * <p>A plain textarea gives an author no signal at all: a typo in the JSONata surfaces much later,
 * as a letter that "could not be prepared", with nothing pointing at the mapping. This parses the
 * expression as it is typed and says what is wrong — and, when it parses, which case data it reads,
 * which is the other thing an author wants to check ("am I reading the field I think I am?").
 *
 * <p>An invalid expression is still stored. Refusing to emit would silently discard what the author
 * typed the moment it stops parsing, which is every intermediate state while writing one.
 */
@Component({
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule],
  selector: 'epistola-mapping-field-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mapping-field" data-testid="epistola-mapping-field">
      <textarea
        class="mapping-input"
        data-testid="epistola-mapping-input"
        spellcheck="false"
        [rows]="rows || 8"
        [disabled]="disabled"
        [ngModel]="value || ''"
        (ngModelChange)="onExpressionChange($event)"
      ></textarea>

      <div *ngIf="error" class="mapping-error" data-testid="epistola-mapping-error">
        {{ 'mappingFieldInvalid' | pluginTranslate: pluginId | async }}: {{ error }}
      </div>

      <div
        *ngIf="!error && referencedPaths.length"
        class="mapping-hint"
        data-testid="epistola-mapping-reads"
      >
        {{ 'mappingFieldReads' | pluginTranslate: pluginId | async }}:
        <code *ngFor="let path of referencedPaths">{{ path }}</code>
      </div>

      <div
        *ngIf="!error && !referencedPaths.length && (value || '').trim()"
        class="mapping-hint"
        data-testid="epistola-mapping-reads-nothing"
      >
        {{ 'mappingFieldReadsNothing' | pluginTranslate: pluginId | async }}
      </div>
    </div>
  `,
  styles: [
    `
      .mapping-input {
        width: 100%;
        font-family: 'IBM Plex Mono', monospace;
        font-size: 0.85rem;
        border: 1px solid #ced4da;
        border-radius: 4px;
        padding: 0.5rem;
      }
      .mapping-error {
        color: #da1e28;
        font-size: 0.85rem;
        padding-top: 0.25rem;
      }
      .mapping-hint {
        color: #6c757d;
        font-size: 0.85rem;
        padding-top: 0.25rem;
      }
      .mapping-hint code {
        background: #f4f4f4;
        border-radius: 3px;
        padding: 0 0.25rem;
        margin-right: 0.25rem;
      }
    `,
  ],
})
export class EpistolaMappingFieldComponent implements FormioCustomComponent<string | null> {
  @Input() value: string | null = null;
  @Output() valueChange = new EventEmitter<string | null>();
  @Input() disabled = false;
  @Input() rows?: number;

  readonly pluginId = 'epistola';

  /** The parse error, in JSONata's own words; null while the expression parses. */
  error: string | null = null;
  /** The `$doc`/`$pv` paths the expression reads, as `$doc.aanvrager.naam`. */
  referencedPaths: string[] = [];

  constructor(
    private readonly cdr: ChangeDetectorRef,
    private readonly pluginTranslationService: PluginTranslationService,
  ) {}

  onExpressionChange(expression: string): void {
    this.value = expression;
    this.valueChange.emit(expression);
    this.inspect(expression);
    this.cdr.markForCheck();
  }

  /** Parse for feedback only — what the author typed is kept either way. */
  private inspect(expression: string): void {
    if (!expression?.trim()) {
      this.error = null;
      this.referencedPaths = [];
      return;
    }
    try {
      jsonata(expression);
      this.error = null;
      this.referencedPaths = extractReferencedPaths(expression).map((reference) =>
        reference.path ? `$${reference.scope}.${reference.path}` : `$${reference.scope}`,
      );
    } catch (parseError: any) {
      this.error = parseError?.message ?? String(parseError);
      this.referencedPaths = [];
    }
  }
}
