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
import { FormioCustomComponent } from '@valtimo/components';
import { FormioModule } from '@formio/angular';
import { Subject, Subscription } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { EpistolaPluginService } from '../../services';
import { ComposerData, mergeComposerData, pruneEmpty } from './composer-data';

/** One selectable letter, as configured on the component. */
export interface ComposerTemplateOption {
  templateId: string;
  label?: string;
}

/** What the component stores on the form, and hands to generation afterwards. */
export interface ComposerValue {
  templateId: string;
  catalogId: string;
  /** Everything the letter is rendered with: the mapping's result plus the employee's input. */
  data: ComposerData;
  /** Only what the employee typed, kept separately so it is visible what was changed by hand. */
  inputs: ComposerData;
}

/**
 * Pick a letter, fill in what the case could not supply, and see it rendered.
 *
 * <p>The component holds no per-letter fields of its own: choosing a template asks the backend for
 * that letter's data and a form for whatever the baseline mapping left empty. That is what keeps a
 * form offering fifty letters the same size as one offering three.
 */
@Component({
  standalone: true,
  imports: [CommonModule, FormioModule],
  selector: 'epistola-letter-composer-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="epistola-composer" data-testid="epistola-composer">
      <label class="composer-select-label" [attr.for]="selectId">
        {{ label || 'Choose a letter' }}
      </label>
      <select
        class="composer-select"
        [id]="selectId"
        [disabled]="disabled || !taskInstanceId"
        [value]="selectedTemplateId || ''"
        (change)="onTemplateSelected($any($event.target).value)"
        data-testid="epistola-composer-select"
      >
        <option value="">{{ placeholder || '— choose —' }}</option>
        <option *ngFor="let option of templates" [value]="option.templateId">
          {{ option.label || option.templateId }}
        </option>
      </select>

      <div *ngIf="!taskInstanceId" class="composer-message" data-testid="epistola-composer-no-task">
        Composing a letter is only available from within a user task.
      </div>

      <div *ngIf="loading" class="composer-message" data-testid="epistola-composer-loading">
        Preparing letter…
      </div>

      <div *ngIf="error" class="composer-error" data-testid="epistola-composer-error">
        {{ error }}
      </div>

      <div
        *ngIf="selectedTemplateId && !loading && !error"
        class="composer-body"
        data-testid="epistola-composer-body"
      >
        <div class="composer-inputs" data-testid="epistola-composer-inputs">
          <p
            *ngIf="complete"
            class="composer-message"
            data-testid="epistola-composer-nothing-to-ask"
          >
            This letter needs no further input.
          </p>
          <formio
            *ngIf="!complete && formDefinition"
            [form]="formDefinition"
            [options]="formOptions"
            (change)="onInputsChanged($event)"
            data-testid="epistola-composer-formio"
          ></formio>
        </div>

        <div class="composer-preview" data-testid="epistola-composer-preview">
          <div class="preview-header">
            <span>Preview</span>
          </div>
          <div
            *ngIf="previewLoading"
            class="composer-message"
            data-testid="epistola-composer-preview-loading"
          >
            Generating preview…
          </div>
          <object
            *ngIf="previewUrl && !previewLoading"
            [data]="previewUrl"
            type="application/pdf"
            class="preview-pdf"
            data-testid="epistola-composer-preview-pdf"
          >
            PDF preview not supported in this browser.
          </object>
          <div
            *ngIf="previewError"
            class="composer-error"
            data-testid="epistola-composer-preview-error"
          >
            {{ previewError }}
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .epistola-composer {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }
      .composer-select-label {
        font-weight: 600;
      }
      .composer-select {
        max-width: 28rem;
        padding: 0.4rem;
      }
      .composer-message {
        color: #6c757d;
        padding: 0.25rem 0;
      }
      .composer-error {
        color: #dc3545;
        padding: 0.25rem 0;
      }
      .composer-body {
        display: flex;
        gap: 1rem;
      }
      .composer-inputs,
      .composer-preview {
        flex: 1 1 50%;
        min-width: 0;
      }
      .preview-header {
        font-weight: 600;
        padding-bottom: 0.25rem;
      }
      .preview-pdf {
        width: 100%;
        height: 40rem;
        border: 1px solid #dee2e6;
      }
    `,
  ],
})
export class EpistolaLetterComposerComponent
  implements FormioCustomComponent<ComposerValue | null>, OnChanges, OnDestroy
{
  @Input() value: ComposerValue | null = null;
  @Output() valueChange = new EventEmitter<ComposerValue | null>();

  /** Letters on offer, from the component's settings. */
  @Input() templates: ComposerTemplateOption[] = [];
  @Input() label?: string;
  @Input() placeholder?: string;
  /** Set by the Formio wrapper from the server-prefilled carrier field. */
  @Input() taskInstanceId?: string;
  /** Part of Valtimo's custom-component contract; a read-only form offers no letter to compose. */
  @Input() disabled = false;

  selectedTemplateId: string | null = null;
  catalogId: string | null = null;
  formDefinition: any = null;
  complete = false;
  loading = false;
  error: string | null = null;

  previewUrl: SafeResourceUrl | null = null;
  previewLoading = false;
  previewError: string | null = null;

  readonly selectId = `epistola-composer-${Math.random().toString(36).slice(2, 9)}`;
  readonly formOptions: any = {
    noAlerts: true,
    buttonSettings: { showCancel: false, showSubmit: false, showPrevious: false, showNext: false },
  };

  /** What the mapping produced; the employee's input is laid over this, never into it. */
  private mappedData: ComposerData = {};
  private previewSubject = new Subject<ComposerData>();
  private previewSubscription?: Subscription;
  private prepareSubscription?: Subscription;
  private currentBlobUrl: string | null = null;

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly cdr: ChangeDetectorRef,
    private readonly sanitizer: DomSanitizer,
  ) {
    this.previewSubscription = this.previewSubject
      .pipe(debounceTime(1000))
      .subscribe((data) => this.loadPreview(data));
  }

  ngOnChanges(changes: SimpleChanges): void {
    // Restore an earlier selection when the form is reopened, and pick up the task id, which the
    // wrapper can set after the first render.
    if (changes['value'] && this.value?.templateId && !this.selectedTemplateId) {
      this.selectedTemplateId = this.value.templateId;
    }
    if (this.taskInstanceId && this.selectedTemplateId && !this.formDefinition && !this.loading) {
      this.prepare(this.selectedTemplateId);
    }
  }

  ngOnDestroy(): void {
    this.prepareSubscription?.unsubscribe();
    this.previewSubscription?.unsubscribe();
    this.revokePreview();
  }

  onTemplateSelected(templateId: string): void {
    this.selectedTemplateId = templateId || null;
    this.formDefinition = null;
    this.error = null;
    this.previewError = null;
    this.revokePreview();

    if (!this.selectedTemplateId) {
      this.emit(null);
      this.cdr.markForCheck();
      return;
    }
    this.prepare(this.selectedTemplateId);
  }

  onInputsChanged(event: any): void {
    if (!event?.data || !this.selectedTemplateId) {
      return;
    }
    const inputs = pruneEmpty(event.data);
    const data = mergeComposerData(this.mappedData, inputs);
    this.emit({
      templateId: this.selectedTemplateId,
      catalogId: this.catalogId ?? '',
      data,
      inputs,
    });
    this.previewSubject.next(data);
  }

  private prepare(templateId: string): void {
    if (!this.taskInstanceId) {
      return;
    }
    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();

    this.prepareSubscription?.unsubscribe();
    this.prepareSubscription = this.epistolaPluginService
      .composerPrepare({ taskId: this.taskInstanceId, templateId })
      .subscribe({
        next: (prepared) => {
          this.mappedData = prepared.data ?? {};
          this.catalogId = prepared.catalogId;
          this.formDefinition = prepared.form;
          this.complete = prepared.complete;
          this.loading = false;
          this.emit({
            templateId,
            catalogId: prepared.catalogId,
            data: this.mappedData,
            inputs: {},
          });
          // Show the letter as the case alone would produce it, before anything is typed.
          this.previewSubject.next(this.mappedData);
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.formDefinition = null;
          this.loading = false;
          this.error = err?.error?.error || 'This letter could not be prepared.';
          this.cdr.markForCheck();
        },
      });
  }

  private loadPreview(data: ComposerData): void {
    if (!this.taskInstanceId || !this.selectedTemplateId) {
      return;
    }
    this.previewLoading = true;
    this.previewError = null;
    this.revokePreview();
    this.cdr.markForCheck();

    this.epistolaPluginService
      .composerPreviewToBlob({
        taskId: this.taskInstanceId,
        templateId: this.selectedTemplateId,
        data,
      })
      .subscribe({
        next: (blob) => {
          this.currentBlobUrl = URL.createObjectURL(blob);
          this.previewUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.currentBlobUrl);
          this.previewLoading = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.previewUrl = null;
          this.previewLoading = false;
          this.readError(err, (message) => {
            this.previewError = message;
            this.cdr.markForCheck();
          });
        },
      });
  }

  /**
   * A refused render arrives as a Blob body, because the request asked for a PDF. Read it as text
   * so the template's own complaint reaches the employee instead of a generic failure.
   */
  private readError(err: any, done: (message: string) => void): void {
    const fallback = 'Preview could not be generated';
    if (err?.error instanceof Blob) {
      err.error
        .text()
        .then((text: string) => {
          try {
            const body = JSON.parse(text);
            done(body.details || body.error || fallback);
          } catch {
            done(fallback);
          }
        })
        .catch(() => done(fallback));
      return;
    }
    done(err?.error?.error || fallback);
  }

  private emit(value: ComposerValue | null): void {
    this.value = value;
    this.valueChange.emit(value);
  }

  private revokePreview(): void {
    if (this.currentBlobUrl) {
      URL.revokeObjectURL(this.currentBlobUrl);
      this.currentBlobUrl = null;
    }
    this.previewUrl = null;
  }
}
