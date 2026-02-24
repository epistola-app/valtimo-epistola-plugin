import {Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpClient} from '@angular/common/http';
import {FormioCustomComponent} from '@valtimo/components';

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

  constructor(private readonly http: HttpClient) {}

  hasRequiredData(): boolean {
    return !!(this.value?.documentId && this.value?.tenantId);
  }

  download(): void {
    if (!this.hasRequiredData() || this.downloading) {
      return;
    }

    this.downloading = true;
    this.error = null;

    const {documentId, tenantId} = this.value;
    const url = `/api/v1/plugin/epistola/documents/${encodeURIComponent(documentId)}/download`
      + `?tenantId=${encodeURIComponent(tenantId)}`
      + `&filename=${encodeURIComponent(this.filename)}`;

    this.http.get(url, {responseType: 'blob'}).subscribe({
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
