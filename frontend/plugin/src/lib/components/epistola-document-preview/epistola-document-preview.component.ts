import {ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpClient} from '@angular/common/http';
import {DomSanitizer, SafeResourceUrl} from '@angular/platform-browser';
import {FormioCustomComponent, FormIoStateService} from '@valtimo/components';
import {ConfigService} from '@valtimo/shared';

@Component({
  standalone: true,
  imports: [CommonModule],
  selector: 'epistola-document-preview-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="epistola-preview-panel">
      <div class="preview-header">
        <span>{{ label || 'Document Preview' }}</span>
        <button type="button" class="preview-refresh" [disabled]="loading" (click)="refresh()">
          <i class="mdi mdi-refresh mr-1"></i>
          {{ loading ? 'Generating...' : 'Refresh' }}
        </button>
      </div>
      <div class="preview-body">
        <div *ngIf="loading" class="preview-loading">
          <i class="mdi mdi-loading mdi-spin"></i> Generating preview...
        </div>
        <div *ngIf="error && !loading" class="preview-error">{{ error }}</div>
        <object
          *ngIf="previewUrl && !loading"
          [data]="previewUrl"
          type="application/pdf"
          class="preview-pdf"
        >
          PDF preview is not supported in this browser.
        </object>
        <div *ngIf="!previewUrl && !loading && !error" class="preview-empty">
          Click Refresh to generate a preview
        </div>
      </div>
    </div>
  `,
  styles: [`
    .epistola-preview-panel {
      border: 1px solid #dee2e6;
      border-radius: 4px;
      background: #f8f9fa;
      display: flex;
      flex-direction: column;
    }
    .preview-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.5rem 1rem;
      border-bottom: 1px solid #dee2e6;
      font-weight: bold;
      color: #495057;
    }
    .preview-refresh {
      background: none;
      border: 1px solid #6c757d;
      border-radius: 4px;
      color: #6c757d;
      padding: 0.25rem 0.75rem;
      font-size: 0.8rem;
      cursor: pointer;
      display: flex;
      align-items: center;
    }
    .preview-refresh:hover:not(:disabled) {
      background: #e9ecef;
    }
    .preview-refresh:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .preview-body {
      display: flex;
      flex-direction: column;
      min-height: 500px;
    }
    .preview-loading {
      padding: 2rem;
      text-align: center;
      color: #6c757d;
      font-style: italic;
    }
    .preview-error {
      padding: 1rem;
      color: #dc3545;
    }
    .preview-pdf {
      width: 100%;
      flex: 1;
      min-height: 500px;
    }
    .preview-empty {
      padding: 2rem;
      text-align: center;
      color: #6c757d;
      font-style: italic;
    }
  `]
})
export class EpistolaDocumentPreviewComponent implements FormioCustomComponent<null>, OnChanges, OnDestroy {
  @Input() value!: null;
  @Output() valueChange = new EventEmitter<null>();

  @Input() disabled = false;
  @Input() label = 'Document Preview';
  @Input() sourceActivityId?: string;

  loading = false;
  error: string | null = null;
  previewUrl: SafeResourceUrl | null = null;
  private loaded = false;
  private currentBlobUrl: string | null = null;
  private readonly apiEndpoint: string;

  constructor(
    private readonly http: HttpClient,
    private readonly sanitizer: DomSanitizer,
    private readonly configService: ConfigService,
    private readonly formIoStateService: FormIoStateService,
    private readonly cdr: ChangeDetectorRef
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.loaded) {
      this.loaded = true;
      this.loadPreview();
    }
  }

  ngOnDestroy(): void {
    this.revokeBlobUrl();
  }

  refresh(): void {
    this.loadPreview();
  }

  private loadPreview(): void {
    const documentId = this.formIoStateService.documentId;
    const processInstanceId = this.formIoStateService.processInstanceId;

    if (!documentId) {
      this.error = 'Could not determine document ID from context.';
      this.cdr.markForCheck();
      return;
    }

    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();
    this.revokeBlobUrl();

    this.http.post(`${this.apiEndpoint}/preview`, {
      documentId,
      processInstanceId: processInstanceId || null,
      sourceActivityId: this.sourceActivityId || null,
      overrides: null
    }, {responseType: 'blob'}).subscribe({
      next: (blob) => {
        this.currentBlobUrl = URL.createObjectURL(blob);
        this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.currentBlobUrl);
        this.error = null;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.previewUrl = null;
        if (err.error instanceof Blob) {
          err.error.text().then((text: string) => {
            try {
              const body = JSON.parse(text);
              this.error = body.details || body.error || 'Preview could not be generated';
            } catch {
              this.error = 'Preview could not be generated';
            }
            this.loading = false;
            this.cdr.markForCheck();
          });
        } else {
          this.error = err.error?.error || 'Preview could not be generated';
          this.loading = false;
          this.cdr.markForCheck();
        }
      }
    });
  }

  private revokeBlobUrl(): void {
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
      this.currentBlobUrl = null;
      this.previewUrl = null;
    }
  }
}
