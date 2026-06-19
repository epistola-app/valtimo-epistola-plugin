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
import { EpistolaPluginService } from '../../services';
import { shouldLoadPreview } from './preview-utils';
import {
  isLegacyOverrideMapping,
  legacyOverrideToJsonata,
} from '../override-builder/legacy-override-converter';

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
        <div class="design-section" *ngIf="overrideExpression">
          <div class="design-label">Input Overrides ($form)</div>
          <pre class="design-expression">{{ overrideExpression }}</pre>
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
          <label
            *ngIf="overrideMapping"
            class="preview-autorefresh"
            title="Automatically refresh the preview as you fill in the form"
          >
            <input
              type="checkbox"
              [checked]="autoRefreshEnabled"
              (change)="onToggleAutoRefresh($event)"
            />
            Auto-refresh
          </label>
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
        gap: 0.75rem;
      }
      .preview-autorefresh {
        display: flex;
        align-items: center;
        gap: 0.3rem;
        font-size: 0.8rem;
        font-weight: normal;
        color: #495057;
        cursor: pointer;
        margin: 0;
        white-space: nowrap;
      }
      .preview-autorefresh input {
        cursor: pointer;
        margin: 0;
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
      .design-expression {
        font-family: monospace;
        font-size: 0.8rem;
        color: #212529;
        background: #eef0f2;
        border-radius: 4px;
        padding: 0.5rem;
        margin: 0.25rem 0 0;
        white-space: pre-wrap;
        word-break: break-word;
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
  /**
   * The override mapping: a JSONata expression string over `$form`, or — for
   * not-yet-re-saved forms — the legacy `form:`-ref object.
   */
  @Input() overrideMapping?: string | Record<string, any>;
  /**
   * Task id forwarded by the Formio wrapper from the server-prefilled form
   * ({@code epistola:taskId} value resolver), populated in every Valtimo task-open flow.
   */
  @Input() taskInstanceId?: string | null;
  /**
   * The computed input overrides (`{ doc, pv }`) the preview renders with, pushed
   * by the Formio wrapper. Kept separate from the Formio `value`: Valtimo's custom
   * component bridge only mirrors `value` to the DOM (never to Formio's data
   * model), so Formio resets it to `emptyValue` on every redraw — which would
   * cancel the preview. This dedicated input is never touched by Formio.
   */
  @Input() inputOverrides?: Record<string, any> | null;
  /**
   * Forces the Formio wrapper to recompute the input overrides from the live form
   * data. Set by the wrapper for override-driven previews; lets the Refresh button
   * work before the first change (e.g. on initial load with pre-filled fields).
   */
  @Input() requestOverrides?: () => void;
  /**
   * Current auto-refresh state, forwarded by the wrapper (seeded from the builder's
   * `autoRefresh` option, default on). Seeds the header toggle's initial state.
   */
  @Input() autoRefresh?: boolean;
  /**
   * Tells the wrapper to enable/disable auto-refresh (recompute on change/blur).
   * Set by the wrapper for override-driven previews; called by the header toggle.
   */
  @Input() setAutoRefresh?: (enabled: boolean) => void;

  /** Runtime state of the header auto-refresh toggle. */
  autoRefreshEnabled = true;

  loading = false;
  error: string | null = null;
  previewUrl: SafeResourceUrl | null = null;
  designMode = false;
  private initialized = false;
  private currentBlobUrl: string | null = null;
  private previewSubscription?: Subscription;

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly sanitizer: DomSanitizer,
    private readonly formIoStateService: FormIoStateService,
    private readonly cdr: ChangeDetectorRef,
  ) {}

  /**
   * The active task id, forwarded by the Formio wrapper from the server-prefilled
   * form ({@code epistola:taskId} value resolver). Null outside a task context
   * (e.g. Formio builder), in which case the component fails closed.
   */
  private get currentTaskId(): string | null {
    return this.taskInstanceId ?? null;
  }

  /**
   * The override mapping as a JSONata expression for the design-mode summary.
   * Legacy `form:`-ref objects are converted on the fly for display.
   */
  get overrideExpression(): string {
    const mapping = this.overrideMapping;
    if (!mapping) return '';
    return isLegacyOverrideMapping(mapping) ? legacyOverrideToJsonata(mapping) : String(mapping);
  }

  ngOnChanges(changes: SimpleChanges): void {
    // Seed the runtime auto-refresh toggle from the wrapper-forwarded state.
    if (changes['autoRefresh']) {
      this.autoRefreshEnabled = this.autoRefresh !== false;
    }
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

      this.triggerPreview();
      return;
    }

    if (this.designMode) return;

    // React to input-override changes, and to the task id arriving late: the Formio
    // wrapper sets taskInstanceId after attach, so it can land after the first render —
    // re-run the preview once it does, instead of leaving the "only available from
    // within a user task" message until a manual refresh.
    if (changes['inputOverrides'] || changes['taskInstanceId']) {
      this.triggerPreview();
    }
  }

  ngOnDestroy(): void {
    this.previewSubscription?.unsubscribe();
    this.revokeBlobUrl();
  }

  refresh(): void {
    // For an override-driven preview whose value isn't ready yet (e.g. initial load
    // before the overrides have been computed), recompute from the live form data;
    // the resulting value change drives the preview. Otherwise re-fetch directly.
    if (this.requestOverrides && !shouldLoadPreview(this.overrideMapping, this.inputOverrides)) {
      this.requestOverrides();
      return;
    }
    this.triggerPreview();
  }

  /** Toggle auto-refresh for this session; flipping it on triggers an immediate refresh. */
  onToggleAutoRefresh(event: Event): void {
    const enabled = (event.target as HTMLInputElement).checked;
    this.autoRefreshEnabled = enabled;
    // The wrapper owns the change/blur listeners, so it must learn about the flip;
    // turning it on recomputes immediately (handled wrapper-side).
    this.setAutoRefresh?.(enabled);
    this.cdr.markForCheck();
  }

  /**
   * Load the preview only when there is enough data for it. Override-driven
   * previews (those with an override mapping) wait until the mapped form data
   * has been computed; until then they show a placeholder rather than firing a
   * request that Epistola would reject with a 400 for missing required fields.
   */
  private triggerPreview(): void {
    if (!shouldLoadPreview(this.overrideMapping, this.inputOverrides)) {
      this.showWaitingForInput();
      return;
    }
    this.loadPreview();
  }

  private showWaitingForInput(): void {
    this.revokeBlobUrl();
    this.previewSubscription?.unsubscribe();
    this.previewUrl = null;
    this.loading = false;
    this.error = 'Complete the form to generate a preview.';
    this.cdr.markForCheck();
  }

  /**
   * Preview using the explicitly configured process link + input overrides.
   * Requires a runtime task context — the backend authorizes the request against
   * the task's process instance and case document, so all three ids must match.
   */
  private loadPreview(): void {
    if (!this.sourceActivityId) {
      this.error = 'Preview is not configured: set the source activity on the form component.';
      this.cdr.markForCheck();
      return;
    }

    // The backend derives the process instance and case document from the task, so the
    // task id is the only runtime context the request carries.
    const taskId = this.currentTaskId;
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
    this.previewSubscription = this.epistolaPluginService
      .previewToBlob({
        taskId,
        sourceActivityId: this.sourceActivityId,
        inputOverrides: this.inputOverrides || null,
        overrides: null,
      })
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
