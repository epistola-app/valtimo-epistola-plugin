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
    <div class="process-link-selector">
      <label class="selector-label">{{ label || 'Process Link' }}</label>
      <select
        class="selector-dropdown"
        [ngModel]="selectedKey"
        (ngModelChange)="onSelect($event)"
        [disabled]="disabled || loading"
      >
        <option value="">{{ loading ? 'Loading...' : '-- Select a process link --' }}</option>
        <option *ngFor="let entry of filteredEntries" [value]="entryKey(entry)">
          {{ entry.processDefinitionName }} / {{ entry.activityName }} ({{ entry.activityId }})
        </option>
      </select>
      <div *ngIf="error" class="selector-error">{{ error }}</div>
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
        this.filteredEntries = entries.filter((e) => e.actionKey === 'generate-document');
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
