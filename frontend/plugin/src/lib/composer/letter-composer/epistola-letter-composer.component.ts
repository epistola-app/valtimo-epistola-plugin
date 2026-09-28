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
import { PluginTranslatePipeModule, PluginTranslationService } from '@valtimo/plugin';
import { FormioModule } from '@formio/angular';
import { Subject, Subscription } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { EpistolaComposerApiService } from '../composer-api.service';
import {
  ComposerData,
  hasValuesFor,
  mergeComposerData,
  pruneEmpty,
  requiredKeys,
} from './composer-data';
import { readOpenDossierId } from './open-dossier';
import { isSectioned, sectionForm } from './composer-sections';
import { COMPOSER_SCHEMA_VERSION } from '../composer-schema';

/** One selectable letter, as configured on the component. */
export interface ComposerTemplateOption {
  templateId: string;
  label?: string;
}

/** What the component stores on the form, and hands to generation afterwards. */
export interface ComposerValue {
  /**
   * The schema this letter was written with. It is read back by a plugin that may be older or
   * newer than the one that wrote it — a process instance can wait months for its generate task —
   * so the shape says which rules apply. See `composer-schema.ts`.
   */
  schemaVersion: number;
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
  imports: [CommonModule, FormioModule, PluginTranslatePipeModule],
  selector: 'epistola-letter-composer-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="epistola-composer" data-testid="epistola-composer">
      <select
        class="composer-select"
        [id]="selectId"
        [disabled]="disabled || !canCompose"
        [value]="selectedTemplateId || ''"
        (change)="onTemplateSelected($any($event.target).value)"
        data-testid="epistola-composer-select"
      >
        <option value="">
          {{ placeholder || ('composerChoosePlaceholder' | pluginTranslate: pluginId | async) }}
        </option>
        <option *ngFor="let option of offeredTemplates" [value]="option.templateId">
          {{ option.label || option.templateId }}
        </option>
      </select>

      <div *ngIf="!canCompose" class="composer-message" data-testid="epistola-composer-no-task">
        {{ 'composerNeedsContext' | pluginTranslate: pluginId | async }}
      </div>

      <div *ngIf="loading" class="composer-message" data-testid="epistola-composer-loading">
        {{ 'composerPreparing' | pluginTranslate: pluginId | async }}
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
            {{ 'composerNothingToAsk' | pluginTranslate: pluginId | async }}
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
            <span>{{ 'composerPreview' | pluginTranslate: pluginId | async }}</span>
          </div>
          <div
            *ngIf="previewLoading"
            class="composer-message"
            data-testid="epistola-composer-preview-loading"
          >
            {{ 'composerPreviewLoading' | pluginTranslate: pluginId | async }}
          </div>
          <object
            *ngIf="previewUrl && !previewLoading"
            [data]="previewUrl"
            type="application/pdf"
            class="preview-pdf"
            data-testid="epistola-composer-preview-pdf"
          >
            {{ 'composerPreviewUnsupported' | pluginTranslate: pluginId | async }}
          </object>
          <div
            *ngIf="previewError"
            class="composer-error"
            data-testid="epistola-composer-preview-error"
          >
            {{ previewError }}
          </div>
          <div
            *ngIf="awaitingRequired"
            class="composer-message"
            data-testid="epistola-composer-awaiting-input"
          >
            {{ 'composerAwaitingInput' | pluginTranslate: pluginId | async }}
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

  /** Letters on offer, as a hand-written form may carry them. */
  @Input() templates: ComposerTemplateOption[] = [];
  /** Letters on offer, as the settings widget stores them. */
  @Input() letterSet?: { templates?: ComposerTemplateOption[] };
  @Input() label?: string;
  @Input() placeholder?: string;
  /** Set by the Formio wrapper from the server-prefilled carrier field. */
  @Input() taskInstanceId?: string;
  /**
   * Which process a start form starts, by its version-stable key. Optional: the backend works it
   * out from the start form this composer sits on, and only needs telling when two processes offer
   * the same letter from a composer keyed the same way.
   */
  @Input() processDefinitionKey?: string;
  /**
   * This component's own Form.io key, set by the wrapper. The backend uses it to find *this*
   * composer's settings, which on a start form is what makes a wrong `processDefinitionKey` fail
   * rather than silently use another process's composer.
   */
  @Input() componentKey?: string;
  /** Start mode: the open dossier, from the server-prefilled `epistola:documentId` carrier. */
  @Input() startDocumentId?: string;
  /** Part of Valtimo's custom-component contract; a read-only form offers no letter to compose. */
  @Input() disabled = false;

  /** The plugin whose translations this component uses; constant, but templates need it bound. */
  readonly pluginId = 'epistola';

  selectedTemplateId: string | null = null;
  catalogId: string | null = null;
  formDefinition: any = null;
  complete = false;
  loading = false;
  error: string | null = null;

  previewUrl: SafeResourceUrl | null = null;
  previewLoading = false;
  previewError: string | null = null;
  /** True while the letter still misses a required value, so no preview is attempted. */
  awaitingRequired = false;

  readonly selectId = `epistola-composer-${Math.random().toString(36).slice(2, 9)}`;

  /** What the picker offers, whichever shape the form stores it in. */
  get offeredTemplates(): ComposerTemplateOption[] {
    return this.letterSet?.templates?.length ? this.letterSet.templates : this.templates;
  }
  /**
   * Formio options for the generated form.
   *
   * <p>A stepped form needs its own navigation — and breadcrumbs that can be clicked, so an
   * employee can go straight back to a step rather than paging through the ones between. A plain
   * form gets no buttons at all: the composer is embedded in a Valtimo form that has its own
   * submit, and a second set of buttons inside it would be ambiguous.
   */
  get formOptions(): any {
    const stepped = isSectioned(this.formDefinition);
    return {
      noAlerts: true,
      breadcrumbSettings: { clickable: true },
      buttonSettings: {
        showCancel: false,
        showSubmit: false,
        showPrevious: stepped,
        showNext: stepped,
      },
    };
  }

  /** What the mapping produced; the employee's input is laid over this, never into it. */
  private mappedData: ComposerData = {};
  /**
   * The process the backend worked out this start form belongs to, kept from the prepare response.
   *
   * Naming it on the calls that follow is what keeps the preview cheap: finding it reads every
   * deployed definition's process links, and the preview fires on every edit.
   */
  private resolvedProcessDefinitionKey: string | null = null;
  private requiredInputKeys: string[] = [];
  private previewSubject = new Subject<ComposerData>();
  private previewSubscription?: Subscription;
  private prepareSubscription?: Subscription;
  private currentBlobUrl: string | null = null;

  constructor(
    private readonly composerApi: EpistolaComposerApiService,
    private readonly cdr: ChangeDetectorRef,
    private readonly sanitizer: DomSanitizer,
    private readonly pluginTranslationService: PluginTranslationService,
  ) {
    this.previewSubscription = this.previewSubject
      .pipe(debounceTime(1000))
      .subscribe((data) => this.loadPreview(data));
  }

  ngOnChanges(changes: SimpleChanges): void {
    // Restore an earlier selection when the form is reopened, and pick up the ids, which the
    // wrapper can set after the first render.
    if (changes['value'] && this.value?.templateId && !this.selectedTemplateId) {
      this.selectedTemplateId = this.value.templateId;
    }
    if (this.canCompose && this.selectedTemplateId && !this.formDefinition && !this.loading) {
      this.prepare(this.selectedTemplateId);
    }
  }

  /**
   * Whether this composer is running on a user task.
   *
   * <p>Read from the context rather than authored: the task id arrives through a server-side
   * prefill carrier that only a task form fills, so its presence *is* the answer, and one
   * component configuration works on a task form and a start form at once. Nothing about
   * authorization rides on it — task and start calls are separate endpoints that each check their
   * own permission — so the worst a wrong reading could do is call the endpoint that then refuses.
   */
  get onUserTask(): boolean {
    return !!this.taskInstanceId;
  }

  /**
   * Whether the composer has enough context to authorize a call: a task, or a case to compose an
   * ad-hoc letter for. Without either it stays inert — which is what should happen in the form
   * builder and in design mode.
   */
  get canCompose(): boolean {
    return this.onUserTask || !!this.composedForDocumentId || !!this.processDefinitionKey;
  }

  ngOnDestroy(): void {
    this.prepareSubscription?.unsubscribe();
    this.previewSubscription?.unsubscribe();
    this.revokePreview();
  }

  onTemplateSelected(templateId: string): void {
    this.selectedTemplateId = templateId || null;
    // Another letter may be offered by another process, so what was resolved for the last one
    // says nothing about this one.
    this.resolvedProcessDefinitionKey = null;
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
      schemaVersion: COMPOSER_SCHEMA_VERSION,
      templateId: this.selectedTemplateId,
      catalogId: this.catalogId ?? '',
      data,
      inputs,
    });
    this.requestPreview(data);
  }

  /**
   * Preview the letter once it can be rendered, and otherwise say what is still needed.
   */
  private requestPreview(data: ComposerData): void {
    const ready = hasValuesFor(data, this.requiredInputKeys);
    this.awaitingRequired = !ready;
    if (!ready) {
      this.previewError = null;
      this.previewLoading = false;
      this.revokePreview();
      this.cdr.markForCheck();
      return;
    }
    this.previewSubject.next(data);
  }

  private prepare(templateId: string): void {
    if (!this.canCompose) {
      return;
    }
    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();

    this.prepareSubscription?.unsubscribe();
    this.prepareSubscription = this.prepareRequest(templateId).subscribe({
      next: (prepared) => {
        this.mappedData = prepared.data ?? {};
        this.catalogId = prepared.catalogId;
        this.resolvedProcessDefinitionKey = prepared.processDefinitionKey ?? null;
        this.formDefinition = sectionForm(prepared.form, (step) =>
          this.translate('composerSection').replace('{step}', String(step)),
        );
        this.complete = prepared.complete;
        this.requiredInputKeys = requiredKeys(prepared.form);
        this.loading = false;
        this.emit({
          schemaVersion: COMPOSER_SCHEMA_VERSION,
          templateId,
          catalogId: prepared.catalogId,
          data: this.mappedData,
          inputs: {},
        });
        // Show the letter as the case alone would produce it — but only when the case can
        // actually produce it. Rendering a letter whose required fields are still empty just
        // returns Epistola's validation error, in place of fields the employee was asked to fill.
        this.requestPreview(this.mappedData);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.formDefinition = null;
        this.loading = false;
        this.error = err?.error?.error || this.translate('composerPrepareFailed');
        this.cdr.markForCheck();
      },
    });
  }

  private loadPreview(data: ComposerData): void {
    if (!this.canCompose || !this.selectedTemplateId) {
      return;
    }
    this.previewLoading = true;
    this.previewError = null;
    this.revokePreview();
    this.cdr.markForCheck();

    this.previewRequest(this.selectedTemplateId, data).subscribe({
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
   * The dossier an ad-hoc letter is composed for: the prefilled carrier when Valtimo fills it,
   * otherwise the case on screen (see {@link readOpenDossierId}). Null means a letter for a case
   * that does not exist yet, which is a new-case start form.
   */
  get composedForDocumentId(): string | null {
    return this.startDocumentId ?? readOpenDossierId(globalThis.location?.pathname);
  }

  /** The prepare call for wherever this composer turns out to be running. */
  private prepareRequest(templateId: string) {
    return this.onUserTask
      ? this.composerApi.composerPrepare({
          taskId: this.taskInstanceId!,
          templateId,
          componentKey: this.componentKey,
        })
      : this.composerApi.composerPrepareStart({
          processDefinitionKey: this.processDefinitionKey,
          documentId: this.composedForDocumentId,
          templateId,
          componentKey: this.componentKey,
        });
  }

  /** The preview call for wherever this composer turns out to be running. */
  private previewRequest(templateId: string, data: ComposerData) {
    return this.onUserTask
      ? this.composerApi.composerPreviewToBlob({
          taskId: this.taskInstanceId!,
          templateId,
          componentKey: this.componentKey,
          data,
        })
      : this.composerApi.composerPreviewStartToBlob({
          // What prepare resolved, else what the author named — never nothing, so the backend
          // does not search again on every edit.
          processDefinitionKey: this.resolvedProcessDefinitionKey ?? this.processDefinitionKey,
          documentId: this.composedForDocumentId,
          templateId,
          componentKey: this.componentKey,
          data,
        });
  }

  /**
   * A refused render arrives as a Blob body, because the request asked for a PDF. Read it as text
   * so the template's own complaint reaches the employee instead of a generic failure.
   */
  private readError(err: any, done: (message: string) => void): void {
    const fallback = this.translate('composerPreviewFailed');
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

  /** A plugin translation, for the messages that are built in code rather than in the template. */
  private translate(key: string): string {
    return this.pluginTranslationService.instant(key, this.pluginId);
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
