import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  OnDestroy,
  OnInit,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormioCustomComponent, FormIoStateService } from '@valtimo/components';
import { Subscription } from 'rxjs';
import { EpistolaTaskContextService } from '../../services';

/**
 * Inline panel that renders an already-generated Epistola PDF in the user's task
 * form. Reads the Epistola PDF id and tenant id from named process variables on
 * the caller's process instance via the {@code /documents/download} endpoint
 * (with {@code disposition=inline}).
 *
 * <p>Use this component for the "view the previously-generated document"
 * use case (B). For the "preview what would be generated right now" use case (A),
 * use {@code epistola-document-preview} instead.
 */
@Component({
  standalone: true,
  imports: [CommonModule],
  selector: 'epistola-generated-document-preview-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Design-time: show a placeholder -->
    <div *ngIf="designMode" class="epistola-preview-panel">
      <div class="preview-header">
        <span>{{ label || 'Document Preview' }}</span>
      </div>
      <div class="preview-body design-info">
        <div class="design-section">
          <div class="design-label">Document ID variable</div>
          <div class="design-value">{{ documentIdVariable }}</div>
          <div class="design-label">Tenant ID variable</div>
          <div class="design-value">{{ tenantIdVariable }}</div>
        </div>
      </div>
    </div>

    <!-- Runtime view -->
    <div *ngIf="!designMode" class="epistola-preview-panel">
      <div class="preview-header">
        <span>{{ label || 'Document Preview' }}</span>
        <button type="button" class="preview-refresh" [disabled]="loading" (click)="refresh()">
          <i class="mdi mdi-refresh mr-1"></i>
          {{ loading ? 'Loading...' : 'Refresh' }}
        </button>
      </div>
      <div class="preview-body">
        <div *ngIf="loading" class="preview-loading">Loading document...</div>
        <div *ngIf="error && !loading" class="preview-unavailable">
          <i class="mdi mdi-information-outline"></i>
          {{ error }}
        </div>
        <object
          *ngIf="previewUrl && !loading"
          [data]="previewUrl"
          type="application/pdf"
          class="preview-pdf"
        >
          PDF preview is not supported in this browser.
        </object>
      </div>
    </div>
  `,
  styles: [
    `
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
      .preview-loading,
      .preview-unavailable {
        padding: 2rem;
        text-align: center;
        color: #6c757d;
        font-style: italic;
      }
      .preview-unavailable i {
        margin-right: 0.25rem;
      }
      .preview-pdf {
        width: 100%;
        flex: 1;
        min-height: 500px;
      }
      .design-info {
        padding: 1rem;
        min-height: auto;
      }
      .design-section {
        margin-bottom: 0.5rem;
      }
      .design-label {
        font-size: 0.7rem;
        text-transform: uppercase;
        color: #868e96;
        font-weight: 600;
        letter-spacing: 0.05em;
      }
      .design-value {
        font-family: monospace;
        font-size: 0.85rem;
        color: #212529;
        margin-bottom: 0.25rem;
      }
    `,
  ],
})
export class EpistolaGeneratedDocumentPreviewComponent
  implements FormioCustomComponent<unknown>, OnInit, OnDestroy
{
  @Input() value: unknown;
  @Output() valueChange = new EventEmitter<unknown>();

  @Input() disabled = false;
  @Input() label = 'Document Preview';

  /** Process-variable name holding the Epistola PDF id. Default: epistolaDocumentId. */
  @Input() documentIdVariable = 'epistolaDocumentId';

  /** Process-variable name holding the Epistola tenant id. Default: epistolaTenantId. */
  @Input() tenantIdVariable = 'epistolaTenantId';

  loading = false;
  error: string | null = null;
  previewUrl: SafeResourceUrl | null = null;
  designMode = false;

  private currentBlobUrl: string | null = null;
  private subscription?: Subscription;

  constructor(
    private readonly http: HttpClient,
    private readonly sanitizer: DomSanitizer,
    private readonly formIoStateService: FormIoStateService,
    private readonly taskContext: EpistolaTaskContextService,
    private readonly cdr: ChangeDetectorRef,
  ) {}

  ngOnInit(): void {
    if (!this.formIoStateService.documentId) {
      this.designMode = true;
      this.cdr.markForCheck();
      return;
    }
    this.loadPreview();
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.revokeBlobUrl();
  }

  refresh(): void {
    this.loadPreview();
  }

  private loadPreview(): void {
    const taskId = this.taskContext.taskInstanceId;
    const caseDocumentId = this.formIoStateService.documentId;

    if (!taskId || !caseDocumentId) {
      this.error = 'Preview is only available from within a user task.';
      this.cdr.markForCheck();
      return;
    }

    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();
    this.revokeBlobUrl();

    const url =
      `/api/v1/plugin/epistola/documents/download` +
      `?taskId=${encodeURIComponent(taskId)}` +
      `&caseDocumentId=${encodeURIComponent(caseDocumentId)}` +
      `&documentIdVariable=${encodeURIComponent(this.documentIdVariable)}` +
      `&tenantIdVariable=${encodeURIComponent(this.tenantIdVariable)}` +
      `&filename=preview.pdf` +
      `&disposition=inline`;

    this.subscription?.unsubscribe();
    this.subscription = this.http.get(url, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        this.currentBlobUrl = URL.createObjectURL(blob);
        this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.currentBlobUrl);
        this.error = null;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.previewUrl = null;
        if (err.status === 404) {
          this.error = 'Document is nog niet gegenereerd.';
        } else {
          this.error = 'Document kon niet geladen worden.';
        }
        this.loading = false;
        this.cdr.markForCheck();
      },
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
