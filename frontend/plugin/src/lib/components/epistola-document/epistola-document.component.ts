/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

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
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormioCustomComponent, FormIoStateService } from '@valtimo/components';
import { Subscription } from 'rxjs';
import { DownloadDocumentRequest, EpistolaPluginService } from '../../services';
import { pdfViewUrl } from '../../utils/pdf-view';
import { PANEL_MEMORY_KEYS } from '../../utils/floating-panel';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { EpistolaPreviewFrameComponent } from '../epistola-preview-frame/epistola-preview-frame.component';

export type EpistolaDocumentDisplay = 'inline' | 'button' | 'both';

/**
 * Unified Formio component for the after-generation Epistola PDF UX. Reads
 * the PDF id and tenant id from named process variables on the caller's
 * task via the {@code /documents/download} endpoint.
 *
 * <p>Three presentations, all backed by the same backend call:
 * <ul>
 *   <li>{@code display="inline"} — render the PDF inline in a panel.</li>
 *   <li>{@code display="button"} — show a click-to-save download button only.</li>
 *   <li>{@code display="both"} (default) — inline panel with a download icon
 *       in the header.</li>
 * </ul>
 *
 * <p>For the dry-run / what-would-be-generated UX use
 * {@code epistola-document-preview} instead.
 */
@Component({
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, EpistolaPreviewFrameComponent],
  selector: 'epistola-document-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- Design-time placeholder -->
    <div *ngIf="designMode" class="epistola-doc-panel" data-testid="epistola-document-design">
      <div class="doc-header">
        <span>{{ label || 'Document' }}</span>
        <span class="design-tag">design mode</span>
      </div>
      <div class="doc-body design-info">
        <div class="design-section">
          <div class="design-label">Display</div>
          <div class="design-value">{{ display }}</div>
          <div class="design-label">Document variable</div>
          <div class="design-value">{{ documentVariable }}</div>
          <div class="design-label">Tenant ID variable</div>
          <div class="design-value">{{ tenantIdVariable }}</div>
        </div>
      </div>
    </div>

    <!-- Button-only display -->
    <div *ngIf="!designMode && display === 'button'" data-testid="epistola-document-button-view">
      <button
        type="button"
        class="btn btn-outline-primary"
        data-testid="epistola-document-download-button"
        [disabled]="disabled || downloading"
        (click)="download()"
      >
        <i class="mdi mdi-download mr-1"></i>
        {{ downloading ? 'Downloading...' : label || 'Download PDF' }}
      </button>
      <span *ngIf="error" class="text-danger ml-2" data-testid="epistola-document-button-error">{{
        error
      }}</span>
    </div>

    <!-- Inline / both: the letter in the frame every Epistola preview shares -->
    <epistola-preview-frame
      *ngIf="!designMode && display !== 'button'"
      [title]="label || 'Document'"
      [memoryKey]="memoryKey"
      [windowUrl]="currentBlobUrl"
      testId="epistola-document-inline-view"
    >
      <button
        previewControls
        *ngIf="display === 'both'"
        type="button"
        class="cds--btn cds--btn--ghost cds--btn--sm epistola-preview-frame__icon-button"
        data-testid="epistola-document-download-icon"
        [disabled]="disabled || downloading"
        [attr.title]="
          (downloading ? 'previewDownloading' : 'previewDownload')
            | pluginTranslate: pluginId
            | async
        "
        [attr.aria-label]="
          (downloading ? 'previewDownloading' : 'previewDownload')
            | pluginTranslate: pluginId
            | async
        "
        (click)="download()"
      >
        <i class="mdi mdi-download" aria-hidden="true"></i>
      </button>
      <button
        previewControls
        type="button"
        class="cds--btn cds--btn--ghost cds--btn--sm epistola-preview-frame__icon-button"
        data-testid="epistola-document-refresh"
        [disabled]="loading"
        [attr.title]="'previewRefresh' | pluginTranslate: pluginId | async"
        [attr.aria-label]="'previewRefresh' | pluginTranslate: pluginId | async"
        (click)="refresh()"
      >
        <i class="mdi mdi-refresh" aria-hidden="true"></i>
      </button>
      <div
        *ngIf="loading"
        class="epistola-preview-frame__message"
        data-testid="epistola-document-loading"
      >
        {{ 'previewLoadingDocument' | pluginTranslate: pluginId | async }}
      </div>
      <div
        *ngIf="error && !loading"
        class="epistola-preview-frame__message"
        data-testid="epistola-document-error"
      >
        <i class="mdi mdi-information-outline" aria-hidden="true"></i>
        {{ error }}
      </div>
      <object
        *ngIf="previewUrl && !loading"
        [data]="previewUrl"
        type="application/pdf"
        class="epistola-preview-frame__pdf"
        data-testid="epistola-document-pdf"
      >
        {{ 'previewUnsupported' | pluginTranslate: pluginId | async }}
      </object>
    </epistola-preview-frame>
  `,
  styles: [
    `
      .epistola-doc-panel {
        border: 1px solid #dee2e6;
        border-radius: 4px;
        background: #f8f9fa;
        display: flex;
        flex-direction: column;
      }
      .doc-header {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 0.5rem 1rem;
        border-bottom: 1px solid #dee2e6;
        font-weight: bold;
        color: #495057;
      }
      .doc-body {
        display: flex;
        flex-direction: column;
        min-height: 500px;
      }
      .doc-loading,
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
      .design-tag {
        font-size: 0.7rem;
        font-weight: normal;
        color: #6c757d;
        font-style: italic;
      }
    `,
  ],
})
export class EpistolaDocumentComponent
  implements FormioCustomComponent<unknown>, OnChanges, OnDestroy
{
  readonly pluginId = 'epistola';
  /** Where this kind of preview remembers its panel. */
  readonly memoryKey = PANEL_MEMORY_KEYS.document;

  @Input() value: unknown;
  @Output() valueChange = new EventEmitter<unknown>();

  @Input() disabled = false;
  @Input() label = 'Document';

  /** How to present the document. `both` (default) shows an inline panel with a download icon. */
  @Input() display: EpistolaDocumentDisplay = 'both';

  /**
   * Process-variable name holding the Epistola result. Default: `epistolaResult`.
   *
   * Type-tolerant on the backend: if the named variable holds a rich result object
   * (`Map<String, Object>` written by `generate-document` and updated by the result
   * collector), the backend digs out the `documentId` key. If it holds a plain
   * String (custom flow that wrote a bare id), the backend uses it as-is.
   */
  @Input() documentVariable = 'epistolaResult';

  /** Process-variable name holding the Epistola tenant id. Default: `epistolaTenantId`. */
  @Input() tenantIdVariable = 'epistolaTenantId';

  /** Filename used for the download disposition. */
  @Input() filename = 'document.pdf';

  /**
   * Task id forwarded by the Formio wrapper from the server-prefilled form
   * ({@code epistola:taskId} value resolver), populated in every Valtimo task-open flow.
   */
  @Input() taskInstanceId?: string | null;

  loading = false;
  downloading = false;
  error: string | null = null;
  previewUrl: SafeResourceUrl | null = null;

  /** The rendered PDF's object URL; the frame shows it in a window of its own when asked. */
  currentBlobUrl: string | null = null;
  private subscription?: Subscription;

  get designMode(): boolean {
    return !this.taskInstanceId && !this.formIoStateService.documentId;
  }

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly sanitizer: DomSanitizer,
    private readonly formIoStateService: FormIoStateService,
    private readonly cdr: ChangeDetectorRef,
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (this.designMode || this.display === 'button') {
      return;
    }
    // The Formio wrapper sets taskInstanceId around attach, so it can arrive after the
    // first change — (re)load the inline document once it's available instead of leaving
    // the "Document is alleen beschikbaar binnen een taak" message until a manual refresh.
    // (For display="button" the download() click reads the task id on demand.)
    if (changes['taskInstanceId'] && this.taskInstanceId) {
      this.loadInline();
    }
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    this.revokeBlobUrl();
  }

  refresh(): void {
    this.loadInline();
  }

  download(): void {
    if (this.designMode || this.downloading) {
      return;
    }
    const request = this.buildRequest('attachment');
    if (!request) return;
    this.downloading = true;
    this.error = null;
    this.cdr.markForCheck();

    this.epistolaPluginService.downloadDocumentBlob(request).subscribe({
      next: (blob) => {
        const objectUrl = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = objectUrl;
        anchor.download = this.filename;
        anchor.click();
        URL.revokeObjectURL(objectUrl);
        this.downloading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = err.status === 404 ? 'Document is nog niet gegenereerd.' : 'Download mislukt.';
        this.downloading = false;
        this.cdr.markForCheck();
      },
    });
  }

  private loadInline(): void {
    const request = this.buildRequest('inline');
    if (!request) return;
    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();
    this.revokeBlobUrl();

    this.subscription?.unsubscribe();
    this.subscription = this.epistolaPluginService.downloadDocumentBlob(request).subscribe({
      next: (blob) => {
        this.currentBlobUrl = URL.createObjectURL(blob);
        this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(
          pdfViewUrl(this.currentBlobUrl),
        );
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.previewUrl = null;
        this.error =
          err.status === 404
            ? 'Document is nog niet gegenereerd.'
            : 'Document kon niet geladen worden.';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  private buildRequest(disposition: 'inline' | 'attachment'): DownloadDocumentRequest | null {
    const taskId = this.taskInstanceId ?? null;
    if (!taskId) {
      this.error = 'Document is alleen beschikbaar binnen een taak.';
      this.cdr.markForCheck();
      return null;
    }
    return {
      taskId,
      documentVariable: this.documentVariable,
      tenantIdVariable: this.tenantIdVariable,
      filename: this.filename,
      disposition,
    };
  }

  private revokeBlobUrl(): void {
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
      this.currentBlobUrl = null;
      this.previewUrl = null;
    }
  }
}
