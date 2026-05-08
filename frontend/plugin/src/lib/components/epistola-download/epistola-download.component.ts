import { Component, EventEmitter, Input, Optional, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormioCustomComponent } from '@valtimo/components';
import { TaskDetailContentComponent } from '@valtimo/task';

export interface DownloadData {
  documentId: string;
  tenantId: string;
}

@Component({
  standalone: true,
  imports: [CommonModule],
  selector: 'epistola-download-component',
  template: `
    <button
      type="button"
      class="btn btn-outline-primary"
      [disabled]="disabled || downloading || !hasRequiredData()"
      (click)="download()"
    >
      <i class="mdi mdi-download mr-1"></i>
      {{ downloading ? 'Downloading...' : buttonLabel }}
    </button>
    <span *ngIf="error" class="text-danger ml-2">{{ error }}</span>
  `,
})
export class EpistolaDownloadComponent implements FormioCustomComponent<DownloadData> {
  @Input() value: DownloadData;
  @Output() valueChange = new EventEmitter<DownloadData>();

  @Input() disabled = false;
  @Input() filename = 'document.pdf';
  @Input() label = 'Download PDF';

  downloading = false;
  error: string | null = null;

  get buttonLabel(): string {
    return this.label || 'Download PDF';
  }

  constructor(
    private readonly http: HttpClient,
    @Optional() private readonly taskDetailContent: TaskDetailContentComponent | null,
  ) {}

  hasRequiredData(): boolean {
    return !!(this.value?.documentId && this.value?.tenantId);
  }

  download(): void {
    if (!this.hasRequiredData() || this.downloading) {
      return;
    }

    const taskId = this.taskDetailContent?.taskInstanceId$.value || null;
    if (!taskId) {
      this.error = 'Download is only available from within a user task.';
      return;
    }

    this.downloading = true;
    this.error = null;

    const { documentId, tenantId } = this.value;
    const url =
      `/api/v1/plugin/epistola/documents/${encodeURIComponent(documentId)}/download` +
      `?tenantId=${encodeURIComponent(tenantId)}` +
      `&filename=${encodeURIComponent(this.filename)}` +
      `&taskId=${encodeURIComponent(taskId)}`;

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
        this.error = 'Download mislukt. Probeer opnieuw.';
        this.downloading = false;
      },
    });
  }
}
