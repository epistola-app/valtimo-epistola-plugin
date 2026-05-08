import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormioCustomComponent, FormIoStateService } from '@valtimo/components';
import { EpistolaTaskContextService } from '../../services';

@Component({
  standalone: true,
  imports: [CommonModule],
  selector: 'epistola-download-component',
  template: `
    <button
      type="button"
      class="btn btn-outline-primary"
      [disabled]="disabled || downloading || designMode"
      (click)="download()"
    >
      <i class="mdi mdi-download mr-1"></i>
      {{
        designMode ? buttonLabel + ' (design mode)' : downloading ? 'Downloading...' : buttonLabel
      }}
    </button>
    <span *ngIf="error" class="text-danger ml-2">{{ error }}</span>
  `,
})
export class EpistolaDownloadComponent implements FormioCustomComponent<unknown> {
  @Input() value: unknown;
  @Output() valueChange = new EventEmitter<unknown>();

  @Input() disabled = false;

  /** Process-variable name holding the Epistola PDF id. Default: epistolaDocumentId. */
  @Input() documentIdVariable = 'epistolaDocumentId';

  /** Process-variable name holding the Epistola tenant id. Default: epistolaTenantId. */
  @Input() tenantIdVariable = 'epistolaTenantId';

  @Input() filename = 'document.pdf';
  @Input() label = 'Download PDF';

  downloading = false;
  error: string | null = null;

  get buttonLabel(): string {
    return this.label || 'Download PDF';
  }

  /** True when rendered in the Formio builder (no live runtime context). */
  get designMode(): boolean {
    return !this.formIoStateService.documentId;
  }

  constructor(
    private readonly http: HttpClient,
    private readonly taskContext: EpistolaTaskContextService,
    private readonly formIoStateService: FormIoStateService,
  ) {}

  download(): void {
    if (this.designMode || this.downloading) {
      return;
    }

    const taskId = this.taskContext.taskInstanceId;
    const caseDocumentId = this.formIoStateService.documentId;
    if (!taskId || !caseDocumentId) {
      this.error = 'Download is only available from within a user task.';
      return;
    }

    this.downloading = true;
    this.error = null;

    const url =
      `/api/v1/plugin/epistola/documents/download` +
      `?taskId=${encodeURIComponent(taskId)}` +
      `&caseDocumentId=${encodeURIComponent(caseDocumentId)}` +
      `&documentIdVariable=${encodeURIComponent(this.documentIdVariable)}` +
      `&tenantIdVariable=${encodeURIComponent(this.tenantIdVariable)}` +
      `&filename=${encodeURIComponent(this.filename)}` +
      `&disposition=attachment`;

    this.http.get(url, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = this.filename;
        anchor.click();
        URL.revokeObjectURL(objectUrl);
        this.downloading = false;
      },
      error: (err) => {
        console.error('Download failed', err);
        if (err.status === 404) {
          this.error = 'Document is nog niet gegenereerd.';
        } else {
          this.error = 'Download mislukt. Probeer opnieuw.';
        }
        this.downloading = false;
      },
    });
  }
}
