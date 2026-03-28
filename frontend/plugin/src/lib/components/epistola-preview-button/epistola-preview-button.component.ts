import {Component, EventEmitter, Input, OnDestroy, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpClient} from '@angular/common/http';
import {DomSanitizer, SafeResourceUrl} from '@angular/platform-browser';
import {FormioCustomComponent} from '@valtimo/components';
import {ConfigService} from '@valtimo/shared';

export interface PreviewButtonData {
  documentId: string;
  tenantId: string;
  processInstanceId?: string;
  processDefinitionKey?: string;
  sourceActivityId?: string;
}

@Component({
  standalone: true,
  imports: [CommonModule],
  selector: 'epistola-preview-button-component',
  template: `
    <button
      type="button"
      class="btn btn-outline-secondary"
      [disabled]="disabled || loading || !hasRequiredData()"
      (click)="openPreview()"
    >
      <i class="mdi mdi-eye mr-1"></i>
      {{ loading ? 'Loading...' : buttonLabel }}
    </button>

    <div *ngIf="modalOpen" class="preview-modal-overlay" (click)="closePreview()">
      <div class="preview-modal-content" (click)="$event.stopPropagation()">
        <div class="preview-modal-header">
          <span>Document Preview</span>
          <button type="button" class="preview-modal-close" (click)="closePreview()">&times;</button>
        </div>
        <div class="preview-modal-body">
          <div *ngIf="previewLoading" class="preview-loading">Generating preview...</div>
          <div *ngIf="previewError" class="preview-error">{{ previewError }}</div>
          <object
            *ngIf="previewUrl && !previewLoading"
            [data]="previewUrl"
            type="application/pdf"
            class="preview-pdf"
          >
            PDF preview is not supported in this browser.
          </object>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .preview-modal-overlay {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      background: rgba(0, 0, 0, 0.5);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 10000;
    }
    .preview-modal-content {
      background: white;
      border-radius: 8px;
      width: 90vw;
      height: 90vh;
      max-width: 1200px;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
    }
    .preview-modal-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid #dee2e6;
      font-weight: bold;
      font-size: 1rem;
    }
    .preview-modal-close {
      background: none;
      border: none;
      font-size: 1.5rem;
      cursor: pointer;
      color: #6c757d;
      line-height: 1;
      padding: 0 0.25rem;
    }
    .preview-modal-close:hover {
      color: #333;
    }
    .preview-modal-body {
      flex: 1;
      overflow: hidden;
      display: flex;
      flex-direction: column;
    }
    .preview-loading, .preview-error {
      padding: 2rem;
      text-align: center;
    }
    .preview-error {
      color: #dc3545;
    }
    .preview-pdf {
      width: 100%;
      flex: 1;
    }
  `]
})
export class EpistolaPreviewButtonComponent implements FormioCustomComponent<PreviewButtonData>, OnDestroy {
  @Input() value!: PreviewButtonData;
  @Output() valueChange = new EventEmitter<PreviewButtonData>();

  @Input() disabled = false;
  @Input() label = 'Preview PDF';

  modalOpen = false;
  loading = false;
  previewLoading = false;
  previewError: string | null = null;
  previewUrl: SafeResourceUrl | null = null;
  private currentBlobUrl: string | null = null;

  private readonly apiEndpoint: string;

  get buttonLabel(): string {
    return this.label || 'Preview PDF';
  }

  constructor(
    private readonly http: HttpClient,
    private readonly sanitizer: DomSanitizer,
    private readonly configService: ConfigService
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
  }

  ngOnDestroy(): void {
    this.revokeBlobUrl();
  }

  hasRequiredData(): boolean {
    return !!(this.value?.documentId && this.value?.tenantId);
  }

  openPreview(): void {
    if (!this.hasRequiredData() || this.loading) return;

    this.modalOpen = true;
    this.previewLoading = true;
    this.previewError = null;
    this.revokeBlobUrl();

    this.http.post(`${this.apiEndpoint}/preview`, {
      documentId: this.value.documentId,
      processInstanceId: this.value.processInstanceId || null,
      processDefinitionKey: this.value.processDefinitionKey || null,
      sourceActivityId: this.value.sourceActivityId || null,
    }, {responseType: 'blob'}).subscribe({
      next: (blob) => {
        this.currentBlobUrl = URL.createObjectURL(blob);
        this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.currentBlobUrl);
        this.previewLoading = false;
      },
      error: (err) => {
        if (err.error instanceof Blob) {
          err.error.text().then((text: string) => {
            try {
              const body = JSON.parse(text);
              this.previewError = body.details || body.error || 'Preview could not be generated';
            } catch {
              this.previewError = 'Preview could not be generated';
            }
          });
        } else {
          this.previewError = 'Preview could not be generated';
        }
        this.previewLoading = false;
      }
    });
  }

  closePreview(): void {
    this.modalOpen = false;
    this.revokeBlobUrl();
    this.previewUrl = null;
    this.previewError = null;
  }

  private revokeBlobUrl(): void {
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
      this.currentBlobUrl = null;
    }
  }
}
