import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Optional,
  Output,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormioCustomComponent, FormIoStateService } from '@valtimo/components';
import { ConfigService } from '@valtimo/shared';
import { TaskDetailContentComponent } from '@valtimo/task';
import { Subscription } from 'rxjs';

@Component({
  standalone: true,
  imports: [CommonModule],
  selector: 'epistola-document-preview-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Design-time view: show configuration summary when no runtime context -->
    <div *ngIf="designMode" class="epistola-preview-panel">
      <div class="preview-header">
        <span>{{ label || 'Document Preview' }}</span>
      </div>
      <div class="preview-body design-info">
        <div class="design-section" *ngIf="sourceActivityId">
          <div class="design-label">Process</div>
          <div class="design-value">{{ processDefinitionKey || '(any)' }}</div>
          <div class="design-label">Activity</div>
          <div class="design-value">{{ sourceActivityId }}</div>
        </div>
        <div class="design-section" *ngIf="overrideMapping">
          <div class="design-label">Input Overrides</div>
          <div *ngFor="let scope of overrideMappingScopes" class="design-mapping">
            <div *ngFor="let entry of overrideMappingEntries(scope)" class="design-entry">
              <span class="design-scope">{{ scope }}</span
              >.{{ entry.path }}
              <i class="mdi mdi-arrow-left"></i>
              <span class="design-field">{{ entry.field }}</span>
            </div>
          </div>
        </div>
        <div *ngIf="!sourceActivityId" class="design-unconfigured">
          Auto-discover mode (no process link configured)
        </div>
      </div>
    </div>

    <!-- Runtime view: actual preview -->
    <div *ngIf="!designMode" class="epistola-preview-panel">
      <div class="preview-header">
        <span>{{ label || 'Document Preview' }}</span>
        <div class="preview-controls">
          <button type="button" class="preview-refresh" [disabled]="loading" (click)="refresh()">
            <i class="mdi mdi-refresh mr-1"></i>
            {{ loading ? 'Generating...' : 'Refresh' }}
          </button>
        </div>
      </div>
      <div class="preview-body">
        <div *ngIf="loading" class="preview-loading">Generating preview...</div>
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
      .design-info {
        padding: 1rem;
        min-height: auto;
      }
      .design-section {
        margin-bottom: 0.75rem;
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
      .design-mapping {
        margin-top: 0.25rem;
      }
      .design-entry {
        font-family: monospace;
        font-size: 0.8rem;
        color: #495057;
        padding: 0.15rem 0;
      }
      .design-scope {
        color: #0d6efd;
      }
      .design-field {
        color: #198754;
      }
      .design-entry i {
        font-size: 0.7rem;
        margin: 0 0.25rem;
        color: #adb5bd;
      }
      .design-unconfigured {
        color: #6c757d;
        font-style: italic;
        font-size: 0.85rem;
      }
    `,
  ],
})
export class EpistolaDocumentPreviewComponent
  implements FormioCustomComponent<Record<string, any> | null>, OnChanges, OnDestroy
{
  @Input() value!: Record<string, any> | null;
  @Output() valueChange = new EventEmitter<Record<string, any> | null>();

  @Input() disabled = false;
  @Input() label = 'Document Preview';
  @Input() processDefinitionKey?: string;
  @Input() sourceActivityId?: string;
  @Input() overrideMapping?: Record<string, any>;

  loading = false;
  error: string | null = null;
  previewUrl: SafeResourceUrl | null = null;
  designMode = false;
  private initialized = false;
  private currentBlobUrl: string | null = null;
  private previewSubscription?: Subscription;
  private readonly apiEndpoint: string;

  constructor(
    private readonly http: HttpClient,
    private readonly sanitizer: DomSanitizer,
    private readonly configService: ConfigService,
    private readonly formIoStateService: FormIoStateService,
    private readonly cdr: ChangeDetectorRef,
    @Optional() private readonly taskDetailContent: TaskDetailContentComponent | null,
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
  }

  /**
   * Resolve the active task id from the surrounding TaskDetailContentComponent.
   * Returns null when used outside a task context (e.g. Formio builder, design mode).
   */
  private get currentTaskId(): string | null {
    return this.taskDetailContent?.taskInstanceId$.value || null;
  }

  get overrideMappingScopes(): string[] {
    return this.overrideMapping ? Object.keys(this.overrideMapping) : [];
  }

  overrideMappingEntries(scope: string): { path: string; field: string }[] {
    const fields = this.overrideMapping?.[scope];
    if (!fields || typeof fields !== 'object') return [];
    return Object.entries(fields).map(([path, field]) => ({ path, field: String(field) }));
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (!this.initialized) {
      this.initialized = true;

      // Detect design mode: no runtime context (Formio builder)
      const documentId = this.formIoStateService.documentId;
      if (!documentId) {
        this.designMode = true;
        this.cdr.markForCheck();
        return;
      }

      if (!this.sourceActivityId) {
        this.error = 'Preview is not configured: set the source activity on the form component.';
        this.cdr.markForCheck();
        return;
      }

      this.loadPreview();
      return;
    }

    // React to value changes (input overrides from the Formio wrapper).
    if (changes['value']) {
      this.loadPreview();
    }
  }

  ngOnDestroy(): void {
    this.previewSubscription?.unsubscribe();
    this.revokeBlobUrl();
  }

  refresh(): void {
    this.loadPreview();
  }

  /**
   * Preview using the explicitly configured process link + input overrides.
   * Requires a runtime task context — the backend authorizes the request against
   * the task's process instance and case document, so all three ids must match.
   */
  private loadPreview(): void {
    const documentId = this.formIoStateService.documentId;
    if (!documentId) {
      this.error = 'Could not determine document ID from context.';
      this.cdr.markForCheck();
      return;
    }

    if (!this.sourceActivityId) {
      this.error = 'Preview is not configured: set the source activity on the form component.';
      this.cdr.markForCheck();
      return;
    }

    const processInstanceId = this.formIoStateService.processInstanceId;
    if (!processInstanceId) {
      this.error = 'Preview is only available from within a running process.';
      this.cdr.markForCheck();
      return;
    }

    const taskId = this.taskDetailContent?.taskInstanceId$.value || null;
    if (!taskId) {
      this.error = 'Preview is only available from within a user task.';
      this.cdr.markForCheck();
      return;
    }

    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();
    this.revokeBlobUrl();

    this.previewSubscription?.unsubscribe();
    this.previewSubscription = this.http
      .post(
        `${this.apiEndpoint}/preview`,
        {
          taskId,
          documentId,
          processDefinitionKey: this.processDefinitionKey || null,
          processInstanceId,
          sourceActivityId: this.sourceActivityId,
          inputOverrides: this.value || null,
          overrides: null,
        },
        {
          responseType: 'blob',
          headers: new HttpHeaders().set('X-Skip-Interceptor', '422'),
        },
      )
      .subscribe({
        next: (blob) => this.handlePreviewSuccess(blob),
        error: (err) => this.handlePreviewError(err),
      });
  }

  private handlePreviewSuccess(blob: Blob): void {
    this.currentBlobUrl = URL.createObjectURL(blob);
    this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.currentBlobUrl);
    this.error = null;
    this.loading = false;
    this.cdr.markForCheck();
  }

  private handlePreviewError(err: any): void {
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

  private revokeBlobUrl(): void {
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
      this.currentBlobUrl = null;
      this.previewUrl = null;
    }
  }
}
