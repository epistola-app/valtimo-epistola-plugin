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
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormioCustomComponent, FormIoStateService } from '@valtimo/components';
import { ConfigService } from '@valtimo/shared';
import { FormioModule } from '@formio/angular';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, switchMap } from 'rxjs/operators';
import { EpistolaPluginService } from '../../services';

@Component({
  standalone: true,
  imports: [CommonModule, FormioModule],
  selector: 'epistola-retry-form-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div *ngIf="loading" class="epistola-retry-loading">Loading form...</div>
    <div *ngIf="error" class="epistola-retry-error">{{ error }}</div>
    <div
      *ngIf="formDefinition && !loading"
      class="epistola-retry-container"
      [class.preview-expanded]="previewExpanded"
    >
      <div class="epistola-retry-form" [hidden]="previewExpanded">
        <formio
          [form]="formDefinition"
          [submission]="submission"
          (change)="onFormChange($event)"
          [options]="formOptions"
        ></formio>
      </div>
      <div class="epistola-retry-preview">
        <div class="preview-header">
          <span>Preview</span>
          <button type="button" class="preview-toggle" (click)="togglePreview()">
            {{ previewExpanded ? 'Show form' : 'Expand' }}
          </button>
        </div>
        <div *ngIf="previewLoading" class="preview-loading">Generating preview...</div>
        <object
          *ngIf="previewUrl && !previewLoading"
          [data]="previewUrl"
          type="application/pdf"
          class="preview-pdf"
        >
          PDF preview not supported in this browser.
        </object>
        <div *ngIf="previewError" class="preview-error">{{ previewError }}</div>
        <div *ngIf="!previewUrl && !previewLoading && !previewError" class="preview-empty">
          Edit fields to see a preview
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .epistola-retry-loading {
        padding: 1rem;
        color: #6c757d;
      }
      .epistola-retry-error {
        padding: 0.5rem;
        color: #dc3545;
      }
      .epistola-retry-container {
        display: flex;
        gap: 1rem;
      }
      .epistola-retry-form {
        flex: 2;
        min-width: 0;
      }
      .epistola-retry-preview {
        flex: 1;
        min-width: 0;
        border: 1px solid #dee2e6;
        border-radius: 4px;
        padding: 1rem;
        background: #f8f9fa;
        display: flex;
        flex-direction: column;
      }
      .preview-expanded .epistola-retry-preview {
        flex: 1;
      }
      .preview-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        font-weight: bold;
        margin-bottom: 0.5rem;
        color: #495057;
      }
      .preview-toggle {
        background: none;
        border: 1px solid #6c757d;
        border-radius: 4px;
        color: #6c757d;
        padding: 0.2rem 0.5rem;
        font-size: 0.75rem;
        cursor: pointer;
      }
      .preview-toggle:hover {
        background: #e9ecef;
      }
      .preview-loading {
        color: #6c757d;
        font-style: italic;
      }
      .preview-pdf {
        width: 100%;
        flex: 1;
        min-height: 500px;
      }
      .preview-expanded .preview-pdf {
        min-height: 80vh;
      }
      .preview-error {
        color: #dc3545;
      }
      .preview-empty {
        color: #6c757d;
        font-style: italic;
      }
    `,
  ],
})
export class EpistolaRetryFormComponent
  implements FormioCustomComponent<string>, OnChanges, OnDestroy
{
  @Input() value!: string;
  @Output() valueChange = new EventEmitter<string>();

  @Input() disabled = false;
  @Input() label = 'Document Data';
  @Input() sourceActivityId?: string;

  formDefinition: any;
  submission: any;
  loading = true;
  error: string | null = null;
  previewUrl: SafeResourceUrl | null = null;
  previewLoading = false;
  previewError: string | null = null;
  previewExpanded = false;
  private loaded = false;
  private loadSubscription?: Subscription;
  private previewSubscription?: Subscription;
  private previewSubject = new Subject<any>();
  private currentBlobUrl: string | null = null;
  private resolvedSourceActivityId?: string;
  private processDefinitionKey?: string;
  private readonly apiEndpoint: string;

  formOptions: any = {
    noAlerts: true,
    buttonSettings: { showCancel: false, showSubmit: false, showPrevious: false, showNext: false },
  };

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly formIoStateService: FormIoStateService,
    private readonly cdr: ChangeDetectorRef,
    private readonly http: HttpClient,
    private readonly sanitizer: DomSanitizer,
    private readonly configService: ConfigService,
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
    // Debounce preview calls
    this.previewSubscription = this.previewSubject.pipe(debounceTime(1500)).subscribe((data) => {
      this.loadPreview(data);
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.loaded) {
      this.loaded = true;
      this.loadForm();
    }
  }

  ngOnDestroy(): void {
    this.loadSubscription?.unsubscribe();
    this.previewSubscription?.unsubscribe();
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
    }
  }

  togglePreview(): void {
    this.previewExpanded = !this.previewExpanded;
    this.cdr.markForCheck();
  }

  onFormChange(event: any): void {
    if (event?.data) {
      const json = JSON.stringify(event.data);
      this.value = json; // Formio reads element.value on change event
      this.valueChange.emit(json);
      // Trigger debounced preview
      this.previewSubject.next(event.data);
    }
  }

  private loadPreview(formData: any): void {
    const documentId = this.formIoStateService.documentId;
    const processInstanceId = this.formIoStateService.processInstanceId;
    if (!documentId || !processInstanceId) return;

    this.previewLoading = true;
    this.previewError = null;
    this.cdr.markForCheck();

    // Revoke previous blob URL to prevent memory leaks
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
      this.currentBlobUrl = null;
    }

    this.http
      .post(
        `${this.apiEndpoint}/preview`,
        {
          documentId,
          processInstanceId,
          sourceActivityId: this.sourceActivityId || null,
          overrides: formData,
        },
        { responseType: 'blob', headers: new HttpHeaders().set('X-Skip-Interceptor', '422') },
      )
      .subscribe({
        next: (blob) => {
          this.currentBlobUrl = URL.createObjectURL(blob);
          this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.currentBlobUrl);
          this.previewError = null;
          this.previewLoading = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.previewUrl = null;
          // Try to extract error message from JSON response body
          if (err.error instanceof Blob) {
            err.error.text().then((text: string) => {
              try {
                const body = JSON.parse(text);
                this.previewError = body.details || body.error || 'Preview could not be generated';
              } catch {
                this.previewError = 'Preview could not be generated';
              }
              this.previewLoading = false;
              this.cdr.markForCheck();
            });
          } else {
            this.previewError = err.error?.error || 'Preview could not be generated';
            this.previewLoading = false;
            this.cdr.markForCheck();
          }
        },
      });
  }

  private loadForm(): void {
    const processInstanceId = this.formIoStateService.processInstanceId;
    const documentId = this.formIoStateService.documentId;
    if (!processInstanceId) {
      this.error = 'Could not determine process instance ID.';
      this.loading = false;
      this.cdr.markForCheck();
      return;
    }

    this.loadSubscription = this.epistolaPluginService
      .getRetryForm(processInstanceId, documentId ?? undefined, this.sourceActivityId)
      .subscribe({
        next: (form) => {
          this.formDefinition = form;
          if (this.value) {
            try {
              this.submission = { data: JSON.parse(this.value) };
            } catch {
              // value is not valid JSON, start fresh
            }
          }
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load retry form', err);
          this.error = 'Failed to load the retry form. Please try again.';
          this.loading = false;
          this.cdr.markForCheck();
        },
      });
  }
}
