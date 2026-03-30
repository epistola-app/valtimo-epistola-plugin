import {ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {HttpClient, HttpHeaders} from '@angular/common/http';
import {DomSanitizer, SafeResourceUrl} from '@angular/platform-browser';
import {FormioCustomComponent, FormIoStateService} from '@valtimo/components';
import {ConfigService} from '@valtimo/shared';
import {PreviewSource} from '../../models';
import {EpistolaPluginService} from '../../services';
import {Subscription} from 'rxjs';

@Component({
  standalone: true,
  imports: [CommonModule],
  selector: 'epistola-document-preview-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="epistola-preview-panel">
      <div class="preview-header">
        <span>{{ label || 'Document Preview' }}</span>
        <div class="preview-controls">
          <select
            *ngIf="sources.length > 1"
            class="preview-select"
            [value]="selectedIndex"
            (change)="onSourceChange($event)"
          >
            <option *ngFor="let source of sources; let i = index" [value]="i">
              {{ source.templateName }} ({{ source.activityId }})
            </option>
          </select>
          <button type="button" class="preview-refresh" [disabled]="loading || discovering" (click)="refresh()">
            <i class="mdi mdi-refresh mr-1"></i>
            {{ loading ? 'Generating...' : 'Refresh' }}
          </button>
        </div>
      </div>
      <div class="preview-body">
        <div *ngIf="discovering" class="preview-loading">
          Discovering documents...
        </div>
        <div *ngIf="loading && !discovering" class="preview-loading">
          Generating preview...
        </div>
        <div *ngIf="error && !loading && !discovering" class="preview-unavailable">
          <i class="mdi mdi-information-outline"></i>
          Preview is niet beschikbaar — niet alle gegevens zijn al ingevuld.
        </div>
        <object
          *ngIf="previewUrl && !loading && !discovering"
          [data]="previewUrl"
          type="application/pdf"
          class="preview-pdf"
        >
          PDF preview is not supported in this browser.
        </object>
        <div *ngIf="!previewUrl && !loading && !discovering && !error && sources.length === 0" class="preview-empty">
          No previewable documents found
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
      flex-wrap: wrap;
      gap: 0.5rem;
    }
    .preview-controls {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .preview-select {
      border: 1px solid #ced4da;
      border-radius: 4px;
      padding: 0.25rem 0.5rem;
      font-size: 0.8rem;
      background: white;
      max-width: 300px;
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
      white-space: nowrap;
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
    .preview-unavailable {
      padding: 1.5rem;
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

  sources: PreviewSource[] = [];
  selectedIndex = 0;
  discovering = false;
  loading = false;
  error: string | null = null;
  previewUrl: SafeResourceUrl | null = null;
  private initialized = false;
  private currentBlobUrl: string | null = null;
  private discoverSubscription?: Subscription;
  private previewSubscription?: Subscription;
  private readonly apiEndpoint: string;

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly http: HttpClient,
    private readonly sanitizer: DomSanitizer,
    private readonly configService: ConfigService,
    private readonly formIoStateService: FormIoStateService,
    private readonly cdr: ChangeDetectorRef
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.initialized) {
      this.initialized = true;
      this.discoverSources();
    }
  }

  ngOnDestroy(): void {
    this.discoverSubscription?.unsubscribe();
    this.previewSubscription?.unsubscribe();
    this.revokeBlobUrl();
  }

  onSourceChange(event: Event): void {
    this.selectedIndex = +(event.target as HTMLSelectElement).value;
    this.loadPreview();
  }

  refresh(): void {
    this.loadPreview();
  }

  private discoverSources(): void {
    const documentId = this.formIoStateService.documentId;
    if (!documentId) {
      this.error = 'Could not determine document ID from context.';
      this.cdr.markForCheck();
      return;
    }

    this.discovering = true;
    this.error = null;
    this.cdr.markForCheck();

    this.discoverSubscription = this.epistolaPluginService.getPreviewSources(documentId).subscribe({
      next: (sources) => {
        this.sources = sources;
        this.discovering = false;
        this.cdr.markForCheck();
        if (sources.length > 0) {
          this.selectedIndex = 0;
          this.loadPreview();
        }
      },
      error: (err) => {
        this.error = err.error?.error || 'Failed to discover preview sources';
        this.discovering = false;
        this.cdr.markForCheck();
      }
    });
  }

  private loadPreview(): void {
    const source = this.sources[this.selectedIndex];
    if (!source) return;

    const documentId = this.formIoStateService.documentId;
    if (!documentId) return;

    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();
    this.revokeBlobUrl();

    this.previewSubscription?.unsubscribe();
    this.previewSubscription = this.http.post(`${this.apiEndpoint}/preview`, {
      documentId,
      processInstanceId: source.processInstanceId,
      sourceActivityId: source.activityId,
      overrides: null
    }, {
      responseType: 'blob',
      headers: new HttpHeaders().set('X-Skip-Interceptor', '422')
    }).subscribe({
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
