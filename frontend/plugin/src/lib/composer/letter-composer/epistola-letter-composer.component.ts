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
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { FormioCustomComponent } from '@valtimo/components';
import { PluginTranslatePipeModule, PluginTranslationService } from '@valtimo/plugin';
import { FormioModule } from '@formio/angular';
import { TranslateService } from '@ngx-translate/core';
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
import { isRendered, PreviewRenderer } from './preview-renderer';
import { FloatingPanel, readPanelMemory, writePanelMemory } from './floating-panel';
import { isSectioned, sectionForm } from './composer-sections';
import { formioLanguage, readerLocale, withLocaleDates } from '../../utils/formio-locale';
import { tagRichText } from '../../utils/rich-text-values';
import { withValidationMessages, type ValidationMessages } from './composer-messages';
import {
  describeFindings,
  type AddressableForm,
  type FieldFinding,
  type ShownFinding,
} from './composer-findings';
import { COMPOSER_SCHEMA_VERSION } from '../composer-schema';

/** One selectable letter, as configured on the component. */
export interface ComposerTemplateOption {
  templateId: string;
  label?: string;
  /**
   * Which catalog it lives in, when the form says. A template id is unique only within a catalog,
   * so this is what distinguishes two letters that share one — sent along so the backend never has
   * to guess between them.
   */
  catalogId?: string;
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
            (ready)="onFormReady($event)"
            data-testid="epistola-composer-formio"
          ></formio>
        </div>

        <div
          #previewPane
          class="composer-preview"
          [class.floating]="floating"
          data-testid="epistola-composer-preview"
        >
          <div class="preview-header" data-testid="epistola-composer-preview-header">
            <span class="preview-title">{{
              'composerPreview' | pluginTranslate: pluginId | async
            }}</span>
            <span
              *ngIf="previewLoading && previewUrl"
              class="preview-status"
              data-testid="epistola-composer-preview-updating"
              >{{ 'composerPreviewUpdating' | pluginTranslate: pluginId | async }}</span
            >
            <span
              *ngIf="previewStale && !previewLoading"
              class="preview-status preview-status--stale"
              data-testid="epistola-composer-preview-stale"
              >{{ 'composerPreviewStale' | pluginTranslate: pluginId | async }}</span
            >
            <div class="preview-controls">
              <label class="preview-autorefresh">
                <input
                  type="checkbox"
                  [checked]="autoRefreshEnabled"
                  (change)="onToggleAutoRefresh($any($event.target).checked)"
                  data-testid="epistola-composer-autorefresh"
                />
                {{ 'composerAutoRefresh' | pluginTranslate: pluginId | async }}
              </label>
              <button
                type="button"
                class="preview-button"
                [disabled]="previewLoading || !canRefresh"
                (click)="refresh()"
                data-testid="epistola-composer-refresh"
              >
                {{ 'composerRefresh' | pluginTranslate: pluginId | async }}
              </button>
              <button
                type="button"
                class="preview-button"
                (click)="togglePopOut()"
                data-testid="epistola-composer-popout"
              >
                {{
                  (floating ? 'composerDock' : 'composerPopOut') | pluginTranslate: pluginId | async
                }}
              </button>
            </div>
          </div>
          <div class="preview-content">
            <div
              *ngIf="previewLoading && !previewUrl"
              class="composer-message"
              data-testid="epistola-composer-preview-loading"
            >
              {{ 'composerPreviewLoading' | pluginTranslate: pluginId | async }}
            </div>
            <!--
            The last letter stays on screen while the next one renders, dimmed, so an edit does not
            blank the preview and lose the employee's place in it.
          -->
            <object
              *ngIf="previewUrl"
              [data]="previewUrl"
              type="application/pdf"
              class="preview-pdf"
              [class.preview-pdf--updating]="previewLoading"
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
            <!--
            The fields Epistola refused, each named as the employee sees it named. Shown here
            rather than under the inputs because most of them have no input: the baseline mapping
            supplies them, so the employee was never asked. See composer-findings.ts.
          -->
            <div
              *ngIf="refusedFields.length"
              class="composer-error"
              data-testid="epistola-composer-refused-fields"
            >
              <p>{{ 'composerRefusedFields' | pluginTranslate: pluginId | async }}</p>
              <ul>
                <li
                  *ngFor="let field of refusedFields"
                  data-testid="epistola-composer-refused-field"
                >
                  <strong>{{ field.label }}</strong
                  >: {{ field.message }}
                </li>
              </ul>
            </div>
            <div
              *ngIf="awaitingRequired"
              class="composer-message"
              data-testid="epistola-composer-awaiting-input"
            >
              {{ 'composerAwaitingInput' | pluginTranslate: pluginId | async }}
            </div>
          </div>
          <div class="preview-resize" aria-hidden="true"></div>
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
      /*
       * Sized by the space the composer is given, not the window: a Valtimo dialog is far narrower
       * than the screen it is on. The preview sits to the right of the form wherever there is room
       * for two columns at all, which is every Valtimo dialog; only a phone-narrow space stacks it
       * below. "Pop out" is the way to give the letter more room than half a dialog.
       */
      .epistola-composer {
        container-type: inline-size;
      }
      .composer-body {
        display: flex;
        flex-direction: column;
        gap: 1rem;
      }
      .composer-inputs,
      .composer-preview {
        min-width: 0;
      }
      @container (min-width: 36rem) {
        .composer-body {
          flex-direction: row;
          align-items: flex-start;
        }
        .composer-inputs,
        .composer-preview {
          flex: 1 1 50%;
        }
        /* Stays in view while a long form scrolls past it. */
        .composer-preview:not(.floating) {
          position: sticky;
          top: 0;
        }
      }
      .preview-header {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.25rem 0.75rem;
        padding-bottom: 0.25rem;
      }
      .preview-title {
        font-weight: 600;
      }
      .preview-status {
        color: #6c757d;
        font-size: 0.875rem;
      }
      .preview-status--stale {
        color: #8a6d00;
      }
      .preview-controls {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-left: auto;
        font-size: 0.875rem;
      }
      .preview-autorefresh {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        margin: 0;
        cursor: pointer;
      }
      .preview-button {
        border: 1px solid #8d8d8d;
        background: #fff;
        padding: 0.125rem 0.5rem;
        cursor: pointer;
      }
      .preview-button:disabled {
        cursor: default;
        opacity: 0.5;
      }
      .preview-pdf {
        width: 100%;
        height: min(80vh, 60rem);
        border: 1px solid #dee2e6;
        transition: opacity 0.15s;
      }
      .preview-pdf--updating {
        opacity: 0.5;
      }
      .preview-resize {
        display: none;
      }
      /* Floating: moved to the body by FloatingPanel, which sets its place and size. */
      .composer-preview.floating {
        display: flex;
        flex-direction: column;
        background: #fff;
        border: 1px solid #c6c6c6;
        box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
        padding: 0.5rem;
      }
      .composer-preview.floating .preview-header {
        cursor: move;
        user-select: none;
      }
      .composer-preview.floating .preview-content {
        flex: 1 1 auto;
        min-height: 0;
        overflow: auto;
      }
      .composer-preview.floating .preview-pdf {
        height: 100%;
      }
      .composer-preview.floating .preview-resize {
        display: block;
        position: absolute;
        right: 0;
        bottom: 0;
        width: 1rem;
        height: 1rem;
        cursor: nwse-resize;
        background: linear-gradient(135deg, transparent 50%, #8d8d8d 50%);
      }
      /* The PDF viewer would take the pointer mid-drag and stop it dead. */
      .composer-preview.dragging .preview-pdf {
        pointer-events: none;
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
  /** Everything this component owns on the form, under the one key it claims. */
  @Input() epistola?: {
    letterSet?: { templates?: ComposerTemplateOption[] };
    stepAfter?: number | string;
    /** Whether the preview follows each edit; on unless the author turned it off. */
    autoRefresh?: boolean;
  };
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
  /**
   * Whether the preview follows each edit. Seeded once from the author's setting, then the
   * employee's to switch: rendering on every change is what makes a long letter feel slow.
   */
  autoRefreshEnabled = true;
  /** True when something was edited since the last render, while auto-refresh is off. */
  previewStale = false;
  /** Whether the preview floats over the page instead of sitting beside the form. */
  floating = readPanelMemory().floating;

  readonly selectId = `epistola-composer-${Math.random().toString(36).slice(2, 9)}`;

  /** What the picker offers, whichever shape the form stores it in. */
  get offeredTemplates(): ComposerTemplateOption[] {
    const offered = this.epistola?.letterSet?.templates;
    return offered?.length ? offered : this.templates;
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
      // Valtimo's language, as Valtimo passes it to its own forms: Form.io takes a number's
      // separators from it, and would otherwise read a Dutch `12,50` as 1250.
      language: this.language,
      breadcrumbSettings: { clickable: true },
      buttonSettings: {
        showCancel: false,
        showSubmit: false,
        showPrevious: stepped,
        showNext: stepped,
      },
    };
  }

  /** The generated form's Form.io instance, once it has mounted. */
  private generatedForm: AddressableForm | null = null;

  /** The fields the last refused render named, each ready to show. */
  refusedFields: readonly ShownFinding[] = [];

  /** What the mapping produced; the employee's input is laid over this, never into it. */
  private mappedData: ComposerData = {};
  /** The letter as it stands now, which a manual refresh renders. */
  private latestData: ComposerData | null = null;
  private autoRefreshSeeded = false;
  /** The floating preview, while it floats. See {@link FloatingPanel}. */
  private panel: FloatingPanel | null = null;
  private previewPaneElement: HTMLElement | null = null;

  /**
   * The preview pane, whenever Angular (re)creates it. It lives inside the block that is rebuilt
   * while a letter is prepared, so a floating pane is let go of when its element goes, and a new
   * one floats again if the employee left it floating.
   */
  @ViewChild('previewPane')
  set previewPane(ref: ElementRef<HTMLElement> | undefined) {
    const element = ref?.nativeElement ?? null;
    if (element === this.previewPaneElement) {
      return;
    }
    this.panel?.release();
    this.panel = null;
    this.previewPaneElement = element;
    if (element && this.floating) {
      this.startFloating(element);
    }
  }
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
  /**
   * The render currently in flight.
   *
   * <p>Separate from {@link previewSubscription}, which is the debounced stream rather than any one
   * render. Two renders can overlap — the debounce spaces requests out, it does not wait for an
   * answer — and if the earlier one is slower its answer arrives last and wins, leaving a preview
   * that does not match what was typed. For a component whose premise is that what was previewed is
   * what gets sent, that is the one thing it must not show.
   */
  private renderSubscription?: Subscription;
  /** Owns the rendered PDF's object URL, and reads a refused render's complaint out of its body. */
  private readonly previews: PreviewRenderer;

  constructor(
    private readonly composerApi: EpistolaComposerApiService,
    private readonly cdr: ChangeDetectorRef,
    private readonly sanitizer: DomSanitizer,
    private readonly pluginTranslationService: PluginTranslationService,
    private readonly translateService: TranslateService,
  ) {
    this.previews = new PreviewRenderer(this.sanitizer, () =>
      this.translate('composerPreviewFailed'),
    );
    this.previewSubscription = this.previewSubject
      .pipe(debounceTime(1000))
      .subscribe((data) => this.loadPreview(data));
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['epistola'] && !this.autoRefreshSeeded) {
      this.autoRefreshEnabled = this.epistola?.autoRefresh !== false;
      this.autoRefreshSeeded = true;
    }
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
    this.panel?.release();
    this.panel = null;
    this.prepareSubscription?.unsubscribe();
    this.previewSubscription?.unsubscribe();
    this.renderSubscription?.unsubscribe();
    this.revokePreview();
  }

  onTemplateSelected(templateId: string): void {
    this.selectedTemplateId = templateId || null;
    this.latestData = null;
    this.previewStale = false;
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
    // What an editor typed says which format it is in, so nothing has to guess later (ADR 0008).
    const inputs = tagRichText(this.formDefinition, pruneEmpty(event.data));
    const data = mergeComposerData(this.mappedData, inputs);
    this.emit({
      schemaVersion: COMPOSER_SCHEMA_VERSION,
      templateId: this.selectedTemplateId,
      catalogId: this.catalogId ?? '',
      data,
      inputs,
    });
    this.latestData = data;
    if (this.autoRefreshEnabled) {
      this.requestPreview(data);
    } else {
      this.previewStale = true;
      this.cdr.markForCheck();
    }
  }

  /** Whether there is a letter to render on demand. */
  get canRefresh(): boolean {
    return !!this.selectedTemplateId && !!this.latestData;
  }

  /** Render the letter as it stands now, without waiting for the debounce. */
  refresh(): void {
    if (!this.latestData) {
      return;
    }
    if (this.markReadiness(this.latestData)) {
      this.loadPreview(this.latestData);
    }
  }

  onToggleAutoRefresh(enabled: boolean): void {
    this.autoRefreshEnabled = enabled;
    // Catch up at once on what was typed while it was off.
    if (enabled && this.previewStale) {
      this.refresh();
    }
    this.cdr.markForCheck();
  }

  /** Float the preview over the page, or put it back beside the form. Remembered per browser. */
  togglePopOut(): void {
    this.floating = !this.floating;
    writePanelMemory({ ...readPanelMemory(), floating: this.floating });
    if (this.floating && this.previewPaneElement) {
      this.startFloating(this.previewPaneElement);
    } else {
      this.panel?.release();
      this.panel = null;
    }
    this.cdr.markForCheck();
  }

  private startFloating(element: HTMLElement): void {
    const handle = element.querySelector<HTMLElement>('.preview-header');
    const resizer = element.querySelector<HTMLElement>('.preview-resize');
    if (!handle || !resizer) {
      return;
    }
    this.panel = new FloatingPanel(
      element,
      handle,
      resizer,
      readPanelMemory().geometry,
      (geometry) => writePanelMemory({ floating: true, geometry }),
    );
  }

  /**
   * Preview the letter once it can be rendered, and otherwise say what is still needed.
   */
  private requestPreview(data: ComposerData): void {
    if (this.markReadiness(data)) {
      this.previewSubject.next(data);
    }
  }

  /** Whether the letter can be rendered; when it cannot, say what is still needed instead. */
  private markReadiness(data: ComposerData): boolean {
    const ready = hasValuesFor(data, this.requiredInputKeys);
    this.awaitingRequired = !ready;
    if (!ready) {
      this.previewError = null;
      this.previewLoading = false;
      this.previewStale = false;
      this.revokePreview();
      this.cdr.markForCheck();
    }
    return ready;
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
        this.formDefinition = sectionForm(
          withLocaleDates(
            withValidationMessages(prepared.form, this.validationMessages),
            this.locale,
          ),
          (step) => this.translate('composerSection').replace('{step}', String(step)),
          this.stepAfter,
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
        // Rendered whatever auto-refresh says: choosing a letter is itself the request to see it.
        this.latestData = this.mappedData;
        this.previewStale = false;
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
    // The previous letter stays on screen until this one arrives; see PreviewRenderer.
    this.previewLoading = true;
    this.previewError = null;
    this.previewStale = false;
    this.cdr.markForCheck();

    // Supersedes whatever was still rendering: a newer preview is the only one worth showing, and
    // unsubscribing cancels the request rather than merely ignoring its answer.
    this.renderSubscription?.unsubscribe();
    this.renderSubscription = this.previews
      .render(this.previewRequest(this.selectedTemplateId, data))
      .subscribe((result) => {
        this.previewLoading = false;
        // Whatever the previous answer named is stale now.
        this.refusedFields = [];

        if (isRendered(result)) {
          this.previewUrl = result.url;
          this.previewError = null;
        } else {
          this.previewUrl = null;
          this.previewError = this.describeRefusal(result.error, result.fields);
        }
        this.cdr.markForCheck();
      });
  }

  /**
   * Puts each field Epistola named under the field, and says what is left over.
   *
   * <p>Returns the message for above the preview: nothing when every finding found a home, since
   * repeating them there would say the same thing twice; otherwise the server's own sentence, which
   * still has to be shown for a letter refused over something no single input holds — a rule about
   * the data as a whole, a row inside a grid, or a field the mapping supplies and the employee was
   * never asked for.
   */
  private describeRefusal(
    error: string,
    findings: readonly FieldFinding[] | undefined,
  ): string | null {
    if (!findings?.length) {
      return error;
    }
    this.refusedFields = describeFindings(this.generatedForm, findings, this.validationMessages);
    // The server's own sentence is dropped once the fields are named: it is a flattened version of
    // the same complaint, and showing both says there are twice as many problems as there are.
    return null;
  }

  /**
   * Keeps the generated form's Form.io instance, which is the only way to put a message on a field
   * the form did not work out for itself.
   *
   * <p>`(ready)` hands over the Angular component; the instance underneath is what addresses
   * components by key.
   */
  onFormReady(formio: { formio?: AddressableForm } | AddressableForm): void {
    const instance = (formio as { formio?: AddressableForm })?.formio;
    this.generatedForm = instance ?? (formio as AddressableForm) ?? null;
  }

  /**
   * The dossier an ad-hoc letter is composed for: the prefilled carrier when Valtimo fills it,
   * otherwise the case on screen (see {@link readOpenDossierId}). Null means a letter for a case
   * that does not exist yet, which is a new-case start form.
   */
  get composedForDocumentId(): string | null {
    return this.startDocumentId ?? readOpenDossierId(globalThis.location?.pathname);
  }

  /** The catalog the offered letter names, when the form named one. */
  private catalogFor(templateId: string): string | undefined {
    return this.offeredTemplates.find((option) => option.templateId === templateId)?.catalogId;
  }

  /** The prepare call for wherever this composer turns out to be running. */
  private prepareRequest(templateId: string) {
    return this.onUserTask
      ? this.composerApi.composerPrepare({
          taskId: this.taskInstanceId!,
          catalogId: this.catalogFor(templateId),
          templateId,
          componentKey: this.componentKey,
        })
      : this.composerApi.composerPrepareStart({
          processDefinitionKey: this.processDefinitionKey,
          documentId: this.composedForDocumentId,
          catalogId: this.catalogFor(templateId),
          templateId,
          componentKey: this.componentKey,
        });
  }

  /** The preview call for wherever this composer turns out to be running. */
  private previewRequest(templateId: string, data: ComposerData) {
    return this.onUserTask
      ? this.composerApi.composerPreviewToBlob({
          taskId: this.taskInstanceId!,
          catalogId: this.catalogFor(templateId),
          templateId,
          componentKey: this.componentKey,
          data,
        })
      : this.composerApi.composerPreviewStartToBlob({
          // What prepare resolved, else what the author named — never nothing, so the backend
          // does not search again on every edit.
          processDefinitionKey: this.resolvedProcessDefinitionKey ?? this.processDefinitionKey,
          documentId: this.composedForDocumentId,
          catalogId: this.catalogFor(templateId),
          templateId,
          componentKey: this.componentKey,
          data,
        });
  }

  /** A plugin translation, for the messages that are built in code rather than in the template. */
  private translate(key: string): string {
    return this.pluginTranslationService.instant(key, this.pluginId);
  }

  /**
   * What the browser says when a generated input breaks the template's own rule.
   *
   * Resolved here rather than wired into Form.io's i18n: these are already in the reader's
   * language, so nothing downstream needs to know which one that is. See
   * [composer-messages.ts](./composer-messages.ts).
   */
  /**
   * How many fields this composer tolerates before stepping, or `undefined` for the default.
   *
   * Read tolerantly: Formio's number field hands back a string on a form that was saved as text,
   * and an author can leave it blank. Anything that is not a positive whole number falls back to
   * the default rather than producing a form with no steps or a step per field — a presentation
   * setting is never worth breaking the letter over.
   */
  private get stepAfter(): number | undefined {
    const configured = Number(this.epistola?.stepAfter);
    return Number.isInteger(configured) && configured > 0 ? configured : undefined;
  }

  /** The language Valtimo is shown in, which decides how numbers and dates are written. */
  private get language(): string {
    return formioLanguage(this.locale);
  }

  /** The same, with its region, which decides the order a date is written in. */
  private get locale(): string {
    return readerLocale(this.translateService.currentLang || this.translateService.defaultLang);
  }

  private get validationMessages(): ValidationMessages {
    return {
      required: this.translate('composerValidationRequired'),
      minLength: this.translate('composerValidationMinLength'),
      maxLength: this.translate('composerValidationMaxLength'),
      min: this.translate('composerValidationMin'),
      max: this.translate('composerValidationMax'),
      pattern: this.translate('composerValidationPattern'),
      patternDescribed: this.translate('composerValidationPatternDescribed'),
      exampleSuffix: this.translate('composerValidationExample'),
    };
  }

  private emit(value: ComposerValue | null): void {
    this.value = value;
    this.valueChange.emit(value);
  }

  private revokePreview(): void {
    this.previews.release();
    this.previewUrl = null;
  }
}
