import {Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormioCustomComponent, FormIoStateService} from '@valtimo/components';
import {FormioModule} from '@formio/angular';
import {Subscription} from 'rxjs';
import {EpistolaPluginService} from '../../services';

@Component({
  standalone: true,
  imports: [CommonModule, FormioModule],
  selector: 'epistola-retry-form-component',
  template: `
    <div *ngIf="loading" class="epistola-retry-loading">Loading form...</div>
    <div *ngIf="error" class="epistola-retry-error">{{ error }}</div>
    <formio
      *ngIf="formDefinition && !loading"
      [form]="formDefinition"
      [submission]="submission"
      (change)="onFormChange($event)"
      [options]="formOptions"
    ></formio>
  `,
  styles: [`
    .epistola-retry-loading {
      padding: 1rem;
      color: #6c757d;
    }
    .epistola-retry-error {
      padding: 0.5rem;
      color: #dc3545;
    }
  `]
})
export class EpistolaRetryFormComponent implements FormioCustomComponent<string>, OnChanges, OnDestroy {
  @Input() value!: string;
  @Output() valueChange = new EventEmitter<string>();

  @Input() disabled = false;
  @Input() label = 'Document Data';
  @Input() sourceActivityId?: string;

  formDefinition: any;
  submission: any;
  loading = true;
  error: string | null = null;
  private loaded = false;
  private loadSubscription?: Subscription;

  formOptions: any = {
    noAlerts: true,
    buttonSettings: {showCancel: false, showSubmit: false, showPrevious: false, showNext: false}
  };

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly formIoStateService: FormIoStateService
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.loaded) {
      this.loaded = true;
      this.loadForm();
    }
  }

  ngOnDestroy(): void {
    this.loadSubscription?.unsubscribe();
  }

  onFormChange(event: any): void {
    if (event?.data) {
      const json = JSON.stringify(event.data);
      this.value = json;  // Formio reads element.value on change event
      this.valueChange.emit(json);
    }
  }

  private loadForm(): void {
    const processInstanceId = this.formIoStateService.processInstanceId;
    const documentId = this.formIoStateService.documentId;
    if (!processInstanceId) {
      this.error = 'Could not determine process instance ID.';
      this.loading = false;
      return;
    }

    this.loadSubscription = this.epistolaPluginService.getRetryForm(
      processInstanceId, documentId ?? undefined, this.sourceActivityId
    ).subscribe({
      next: (form) => {
        this.formDefinition = form;
        if (this.value) {
          try {
            this.submission = {data: JSON.parse(this.value)};
          } catch {
            // value is not valid JSON, start fresh
          }
        }
        this.loading = false;
      },
      error: (err) => {
        console.error('Failed to load retry form', err);
        this.error = 'Failed to load the retry form. Please try again.';
        this.loading = false;
      }
    });
  }
}
