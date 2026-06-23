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
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FormioCustomComponent } from '@valtimo/components';
import { EpistolaAdminService } from '../../services';
import { PluginUsageEntry } from '../../models';
import { Subscription } from 'rxjs';
import { filterGenerateDocumentEntries } from './process-link-selector.util';

export interface ProcessLinkSelection {
  processDefinitionKey: string;
  sourceActivityId: string;
}

@Component({
  standalone: true,
  imports: [CommonModule, FormsModule],
  selector: 'epistola-process-link-selector-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="process-link-selector" data-testid="epistola-process-link-container">
      <label class="selector-label" data-testid="epistola-process-link-label">{{
        label || 'Process Link'
      }}</label>
      <select
        class="selector-dropdown"
        data-testid="epistola-process-link-select"
        [ngModel]="selectedKey"
        (ngModelChange)="onSelect($event)"
        [disabled]="disabled || loading"
      >
        <option value="" data-testid="epistola-process-link-option-empty">
          {{ loading ? 'Loading...' : '-- Select a process link --' }}
        </option>
        <option
          *ngFor="let entry of filteredEntries"
          [value]="entryKey(entry)"
          [attr.data-testid]="'epistola-process-link-option-' + entryKey(entry)"
        >
          {{ entry.processDefinitionName }} / {{ entry.activityName }} ({{ entry.activityId }})
        </option>
      </select>
      <div *ngIf="error" class="selector-error" data-testid="epistola-process-link-error">
        {{ error }}
      </div>
    </div>
  `,
  styles: [
    `
      .process-link-selector {
        margin-bottom: 0.5rem;
      }
      .selector-label {
        display: block;
        font-weight: 600;
        font-size: 0.85rem;
        color: #495057;
        margin-bottom: 0.25rem;
      }
      .selector-dropdown {
        width: 100%;
        border: 1px solid #ced4da;
        border-radius: 4px;
        padding: 0.4rem 0.5rem;
        font-size: 0.85rem;
        background: white;
      }
      .selector-error {
        color: #dc3545;
        font-size: 0.75rem;
        margin-top: 0.25rem;
      }
    `,
  ],
})
export class EpistolaProcessLinkSelectorComponent
  implements FormioCustomComponent<ProcessLinkSelection | null>, OnChanges, OnDestroy
{
  @Input() value!: ProcessLinkSelection | null;
  @Output() valueChange = new EventEmitter<ProcessLinkSelection | null>();

  @Input() disabled = false;
  @Input() label = 'Process Link';

  filteredEntries: PluginUsageEntry[] = [];
  selectedKey = '';
  loading = false;
  error: string | null = null;

  private initialized = false;
  private loadSubscription?: Subscription;

  constructor(
    private readonly adminService: EpistolaAdminService,
    private readonly cdr: ChangeDetectorRef,
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.initialized) {
      this.initialized = true;
      this.loadEntries();
    }
    // Restore selection whenever value changes (Formio may set it after init)
    if (changes['value'] && this.value) {
      this.selectedKey = `${this.value.processDefinitionKey}::${this.value.sourceActivityId}`;
      this.cdr.markForCheck();
    }
  }

  ngOnDestroy(): void {
    this.loadSubscription?.unsubscribe();
  }

  onSelect(key: string): void {
    this.selectedKey = key;
    if (!key) {
      this.value = null;
      this.valueChange.emit(null);
      return;
    }
    const [processDefinitionKey, sourceActivityId] = key.split('::');
    this.value = { processDefinitionKey, sourceActivityId };
    this.valueChange.emit(this.value);
  }

  entryKey(entry: PluginUsageEntry): string {
    return `${entry.processDefinitionKey}::${entry.activityId}`;
  }

  private loadEntries(): void {
    this.loading = true;
    this.cdr.markForCheck();

    this.loadSubscription = this.adminService.getPluginUsage().subscribe({
      next: (entries) => {
        this.filteredEntries = filterGenerateDocumentEntries(entries);
        this.loading = false;

        // Restore selection from value
        if (this.value) {
          this.selectedKey = `${this.value.processDefinitionKey}::${this.value.sourceActivityId}`;
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = 'Failed to load process links';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }
}
