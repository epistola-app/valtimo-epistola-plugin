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
import { PluginTranslatePipeModule } from '@valtimo/plugin';

import { problemWith, rulesFrom, storedFrom, type WriteBackRule } from './write-back';

/**
 * Where a letter's values also belong in the case.
 *
 * <p>An input the employee types is, by default, part of that letter and nothing else. A corrected
 * phone number is usually worth keeping, so a composer may declare rules that put values back:
 * each one a destination in the case and an expression over the letter that was sent.
 *
 * <p>Edited as rows and stored as a map from destination to expression. The direction is the point:
 * keying by destination makes one writer per case path the only representable thing, and lets a
 * destination be computed from more than one input — which a per-input target cannot express.
 *
 * <p>Nothing is validated against the case definition here. Whether `/aanvrager/telefoon` exists in
 * this case type is a question for the case, and answering it in a settings dialog would need a
 * round trip per keystroke; what is checked is the shape of the key and that the expression parses.
 * The rules themselves run when the letter is generated, not here.
 */
@Component({
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule],
  selector: 'epistola-write-back-builder-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="write-back" data-testid="epistola-write-back-builder">
      <p class="write-back-intro">
        {{ 'writeBackIntro' | pluginTranslate: pluginId | async }}
      </p>

      <table *ngIf="rules.length" class="write-back-rules">
        <thead>
          <tr>
            <th>{{ 'writeBackDestination' | pluginTranslate: pluginId | async }}</th>
            <th>{{ 'writeBackExpression' | pluginTranslate: pluginId | async }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let rule of rules; let i = index" data-testid="epistola-write-back-rule">
            <td>
              <input
                type="text"
                class="form-control"
                [ngModel]="rule.destination"
                (ngModelChange)="onDestinationChange(i, $event)"
                [disabled]="disabled"
                [attr.data-testid]="'epistola-write-back-destination-' + i"
                placeholder="doc:/aanvrager/telefoon"
              />
              <small *ngIf="problems[i]?.destination" class="write-back-error">
                {{ problems[i]!.destination! | pluginTranslate: pluginId | async }}
              </small>
            </td>
            <td>
              <input
                type="text"
                class="form-control"
                [ngModel]="rule.expression"
                (ngModelChange)="onExpressionChange(i, $event)"
                [disabled]="disabled"
                [attr.data-testid]="'epistola-write-back-expression-' + i"
                placeholder="$inputs.telefoon"
              />
              <small *ngIf="problems[i]?.expression" class="write-back-error">
                {{ problems[i]!.expression! | pluginTranslate: pluginId | async }}
              </small>
            </td>
            <td class="write-back-remove">
              <button
                type="button"
                class="btn btn-secondary btn-sm"
                (click)="remove(i)"
                [disabled]="disabled"
                [attr.data-testid]="'epistola-write-back-remove-' + i"
                [attr.aria-label]="'writeBackRemove' | pluginTranslate: pluginId | async"
              >
                &times;
              </button>
            </td>
          </tr>
        </tbody>
      </table>

      <button
        type="button"
        class="btn btn-secondary btn-sm"
        (click)="add()"
        [disabled]="disabled"
        data-testid="epistola-write-back-add"
      >
        {{ 'writeBackAdd' | pluginTranslate: pluginId | async }}
      </button>

      <p class="write-back-hint">
        <code>$data</code>
        {{ 'writeBackHintData' | pluginTranslate: pluginId | async }}
        <code>$inputs</code>
        {{ 'writeBackHintInputs' | pluginTranslate: pluginId | async }}
      </p>
    </div>
  `,
  styles: [
    `
      .write-back-rules {
        width: 100%;
        margin-bottom: 0.5rem;
      }
      .write-back-rules th {
        font-size: 0.85rem;
        font-weight: 600;
        text-align: left;
        padding-bottom: 0.25rem;
      }
      .write-back-rules td {
        padding: 0 0.25rem 0.5rem 0;
        vertical-align: top;
      }
      .write-back-remove {
        width: 2.5rem;
      }
      .write-back-error {
        color: #da1e28;
        display: block;
        font-size: 0.8rem;
        padding-top: 0.15rem;
      }
      .write-back-intro,
      .write-back-hint {
        color: #6c757d;
        font-size: 0.85rem;
      }
      .write-back-hint code {
        background: #f4f4f4;
        border-radius: 3px;
        padding: 0 0.25rem;
        margin: 0 0.25rem;
      }
    `,
  ],
})
export class EpistolaWriteBackBuilderComponent implements FormioCustomComponent<Record<
  string,
  string
> | null> {
  @Output() valueChange = new EventEmitter<Record<string, string> | null>();
  @Input() disabled = false;

  readonly pluginId = 'epistola';

  /** The rules as rows, including any the author has not finished typing. */
  rules: WriteBackRule[] = [];

  /** What is wrong with each row, by index; an entry is null when nothing is. */
  problems: (ReturnType<typeof problemWith> | null)[] = [];

  constructor(private readonly cdr: ChangeDetectorRef) {}

  /**
   * The stored map.
   *
   * <p>Read once into rows. Writing back through this setter on every keystroke would drop the row
   * the author is still typing, because a half-written rule is deliberately not stored.
   */
  @Input()
  set value(stored: Record<string, string> | null) {
    if (storedFrom(this.rules) === null && !stored) {
      return;
    }
    if (JSON.stringify(storedFrom(this.rules)) === JSON.stringify(stored ?? null)) {
      return;
    }
    this.rules = rulesFrom(stored);
    this.inspect();
  }

  get value(): Record<string, string> | null {
    return storedFrom(this.rules);
  }

  add(): void {
    this.rules = [...this.rules, { destination: '', expression: '' }];
    this.inspect();
    // Deliberately no emit: an empty row stores nothing, and emitting here would mark the form
    // dirty for a row the author may never fill in.
    this.cdr.markForCheck();
  }

  remove(index: number): void {
    this.rules = this.rules.filter((_, at) => at !== index);
    this.changed();
  }

  onDestinationChange(index: number, destination: string): void {
    this.rules = this.rules.map((rule, at) => (at === index ? { ...rule, destination } : rule));
    this.changed();
  }

  onExpressionChange(index: number, expression: string): void {
    this.rules = this.rules.map((rule, at) => (at === index ? { ...rule, expression } : rule));
    this.changed();
  }

  private changed(): void {
    this.inspect();
    this.valueChange.emit(storedFrom(this.rules));
    this.cdr.markForCheck();
  }

  /** Recomputed on every edit, so a problem appears and clears as it is caused and fixed. */
  private inspect(): void {
    this.problems = this.rules.map((rule, index) => {
      const problem = problemWith(rule, index, this.rules);
      return problem.destination || problem.expression ? problem : null;
    });
  }
}
