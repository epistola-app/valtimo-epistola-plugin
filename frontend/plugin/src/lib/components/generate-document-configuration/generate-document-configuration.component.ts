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
  OnDestroy,
  OnInit,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  FunctionConfigurationComponent,
  PluginConfigurationData,
  PluginTranslatePipeModule,
} from '@valtimo/plugin';
import {
  FormModule,
  FormOutput,
  InputModule,
  SelectedValue,
  SelectItem,
  SelectModule,
} from '@valtimo/components';
import { CaseManagementParams, ManagementContext } from '@valtimo/shared';
import { ProcessLinkStateService } from '@valtimo/process-link';
import { BehaviorSubject, combineLatest, merge, Observable, of, Subject, Subscription } from 'rxjs';
import {
  catchError,
  distinctUntilChanged,
  filter,
  map,
  shareReplay,
  switchMap,
  take,
  takeUntil,
  tap,
} from 'rxjs/operators';
import {
  AsyncResource,
  errorResource,
  ExpressionFunctionInfo,
  GenerateDocumentConfig,
  initialResource,
  JsonataFieldError,
  loadingResource,
  successResource,
  TemplateField,
  ValidateJsonataRequest,
  VariableSuggestions,
} from '../../models';
import { EpistolaPluginService } from '../../services';
import { JsonataEditorComponent } from '../jsonata-editor/jsonata-editor.component';
import { ExpectedStructureComponent } from '../expected-structure/expected-structure.component';
import { MappingBuilderComponent } from '../mapping-builder/mapping-builder.component';
import { MappingPreviewComponent } from '../mapping-preview/mapping-preview.component';
import { isExpression } from '../epistola-document-preview/preview-utils';

export type VariantSelectionMode = 'explicit' | 'attributes';

@Component({
  selector: 'epistola-generate-document-configuration',
  templateUrl: './generate-document-configuration.component.html',
  styleUrls: ['./generate-document-configuration.component.scss'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    FormsModule,
    PluginTranslatePipeModule,
    FormModule,
    InputModule,
    SelectModule,
    ExpectedStructureComponent,
    JsonataEditorComponent,
    MappingBuilderComponent,
    MappingPreviewComponent,
  ],
})
export class GenerateDocumentConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<GenerateDocumentConfig>;
  @Input() selectedPluginConfigurationData$?: Observable<PluginConfigurationData>;
  @Input() context$?: Observable<[ManagementContext, CaseManagementParams]>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<GenerateDocumentConfig> =
    new EventEmitter<GenerateDocumentConfig>();

  catalogs$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  templates$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  variants$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  environments$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  templateFields$ = new BehaviorSubject<AsyncResource<TemplateField[]>>(initialResource([]));

  dataMapping$ = new BehaviorSubject<string>('');
  mappingMode: 'simple' | 'advanced' = 'simple';
  toolsCollapsed = true;
  activeToolTab: 'schema' | 'preview' = 'preview';

  outputFormatOptions: SelectItem[] = [
    { id: 'PDF', text: 'PDF' },
    { id: 'HTML', text: 'HTML' },
  ];

  readonly selectedCatalogId$ = new BehaviorSubject<string>('');
  /** Composite ID: "catalogId/templateId" */
  readonly selectedTemplateId$ = new BehaviorSubject<string>('');

  /**
   * Force-clears the templateId v-select. Triggered when the catalog changes —
   * v-select's `setDefaultSelection` ignores empty-string defaults, so binding
   * `[defaultSelectionId]=""` does NOT reset the dropdown. The `clearSelectionSubject$`
   * input is the supported escape hatch.
   */
  readonly clearTemplateId$ = new Subject<void>();
  /** Same pattern for the explicit-mode variantId v-select. */
  readonly clearVariantId$ = new Subject<void>();

  variantSelectionMode: VariantSelectionMode = 'explicit';
  variantIdExpressionMode = false;
  variantIdExpression = '';
  /**
   * Plain-mode variant id. Tracked outside `<v-form>` because the explicit
   * variant `<v-select>` lives inside `<div class="field-with-fx">`, and
   * v-form's `@ContentChildren(SelectComponent)` query only sees direct
   * children (Angular defaults `descendants: false`).
   */
  variantIdValue = '';
  filenameExpressionMode = false;
  filenameExpression = '';
  /** Plain-mode filename. Tracked outside `<v-form>` for the same reason as `variantIdValue`. */
  filenameValue = '';
  variantAttributeEntries: {
    key: string;
    value: string;
    required: boolean;
    _customKey?: boolean;
    _expressionMode?: boolean;
  }[] = [];
  availableAttributeKeys: string[] = [];
  caseDefinitionKey: string | null = null;
  processVariables: string[] = [];
  expressionFunctions: ExpressionFunctionInfo[] = [];
  variableSuggestions: VariableSuggestions | null = null;
  /** Context variables for the JSONata editor's autocomplete ($doc/$pv/$case). */
  editorContextVariables: Record<string, string[]> = { doc: [], pv: [], case: [] };
  prefillDataMapping: Record<string, any> = {};
  validationErrors$ = new BehaviorSubject<JsonataFieldError[]>([]);

  private readonly destroy$ = new Subject<void>();
  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<Partial<GenerateDocumentConfig> | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);
  private pluginConfigurationId$ = new BehaviorSubject<string>('');

  /** Resolves once with the prefill config (or empty config if none). */
  private prefill$!: Observable<GenerateDocumentConfig | null>;

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly processLinkStateService: ProcessLinkStateService,
    private readonly cdr: ChangeDetectorRef,
  ) {}

  ngOnInit(): void {
    this.prefill$ = this.resolvePrefill$();

    this.initContext();
    this.initPluginConfiguration();
    this.initCascade();
    this.loadExpressionFunctions();
    this.openSaveSubscription();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as Partial<
      GenerateDocumentConfig & { catalogId: string; templateId: string }
    >;
    this.formValue$.next(formValue);

    // When catalog changes, reset template and variant selection.
    // The clear$ subjects force-clear the v-selects' internal `selected$` state —
    // without them the dropdown keeps the previous id and the next v-form emission
    // re-applies it under the new catalog, causing 404s when the template doesn't exist
    // in the newly selected catalog.
    if (formValue.catalogId && formValue.catalogId !== this.selectedCatalogId$.getValue()) {
      this.selectedCatalogId$.next(formValue.catalogId);
      this.selectedTemplateId$.next('');
      this.variantIdValue = '';
      this.clearTemplateId$.next();
      this.clearVariantId$.next();
      return;
    }

    // templateId from v-select is the template ID within the selected catalog
    if (formValue.templateId && formValue.templateId !== this.selectedTemplateId$.getValue()) {
      this.selectedTemplateId$.next(formValue.templateId);
      this.variantIdValue = '';
      this.clearVariantId$.next();
    }

    this.handleValid(formValue);
  }

  onDataMappingChange(expression: string): void {
    this.dataMapping$.next(expression);
    this.revalidate();
  }

  onFilenameValueChange(value: string | undefined): void {
    this.filenameValue = value ?? '';
    this.revalidate();
  }

  onVariantIdValueChange(value: SelectedValue | undefined): void {
    // v-select is single-select here, so SelectedValue narrows to string | number;
    // our variant ids are always strings — coerce defensively.
    this.variantIdValue = value == null || Array.isArray(value) ? '' : String(value);
    this.revalidate();
  }

  toggleFilenameExpressionMode(): void {
    this.filenameExpressionMode = !this.filenameExpressionMode;
    this.revalidate();
  }

  toggleVariantIdExpressionMode(): void {
    this.variantIdExpressionMode = !this.variantIdExpressionMode;
    this.revalidate();
  }

  onVariantSelectionModeChange(mode: VariantSelectionMode): void {
    this.variantSelectionMode = mode;
    if (mode === 'attributes' && this.variantAttributeEntries.length === 0) {
      this.variantAttributeEntries = [{ key: '', value: '', required: true }];
    }
    this.revalidate();
  }

  addAttributeEntry(): void {
    this.variantAttributeEntries = [
      ...this.variantAttributeEntries,
      { key: '', value: '', required: true },
    ];
    this.revalidate();
  }

  removeAttributeEntry(index: number): void {
    this.variantAttributeEntries = this.variantAttributeEntries.filter((_, i) => i !== index);
    this.revalidate();
  }

  onAttributeEntryChange(): void {
    this.revalidate();
  }

  onVariantIdExpressionChange(): void {
    this.revalidate();
  }

  onFilenameExpressionChange(): void {
    this.revalidate();
  }

  onKeySelected(
    entry: { key: string; value: string; required: boolean; _customKey?: boolean },
    value: string,
  ): void {
    if (value === '__custom__') {
      entry._customKey = true;
      entry.key = '';
    } else {
      entry.key = value;
    }
    this.onAttributeEntryChange();
  }

  cancelCustomKey(entry: {
    key: string;
    value: string;
    required: boolean;
    _customKey?: boolean;
  }): void {
    entry._customKey = false;
    entry.key = '';
    this.onAttributeEntryChange();
  }

  private revalidate(): void {
    const currentFormValue = this.formValue$.getValue();
    if (currentFormValue) {
      this.handleValid(currentFormValue);
    }
  }

  private formatAttributes(attributes: Record<string, string>): string {
    const entries = Object.entries(attributes || {});
    if (entries.length === 0) return '';
    return ` (${entries.map(([k, v]) => `${k}=${v}`).join(', ')})`;
  }

  /**
   * Creates a shared observable that resolves once with the prefill config
   * (or null if no prefill is provided). This is used to seed the cascade
   * with initial selection values before any loading starts.
   */
  private resolvePrefill$(): Observable<GenerateDocumentConfig | null> {
    if (!this.prefillConfiguration$) {
      return of(null).pipe(shareReplay(1));
    }
    return this.prefillConfiguration$.pipe(take(1), shareReplay(1));
  }

  private initContext(): void {
    if (this.context$) {
      this.context$
        .pipe(
          takeUntil(this.destroy$),
          filter(([context]) => context === 'case'),
        )
        .subscribe(([, params]) => {
          this.caseDefinitionKey = params.caseDefinitionKey;
          this.cdr.markForCheck();
        });
    }
  }

  private initPluginConfiguration(): void {
    const sources: Observable<string>[] = [];

    if (this.selectedPluginConfigurationData$) {
      sources.push(
        this.selectedPluginConfigurationData$.pipe(
          filter((config) => !!config?.configurationId),
          map((config) => config.configurationId),
        ),
      );
    }

    sources.push(
      this.processLinkStateService.selectedProcessLink$.pipe(
        filter((processLink) => !!processLink?.pluginConfigurationId),
        map((processLink) => processLink.pluginConfigurationId!),
      ),
    );

    merge(...sources)
      .pipe(takeUntil(this.destroy$))
      .subscribe((configurationId) => {
        this.pluginConfigurationId$.next(configurationId);
      });
  }

  /**
   * Sets up the entire reactive cascade:
   *
   *   pluginConfigurationId$ → catalogs (+ environments independently)
   *   prefill + catalogs loaded → seed selectedCatalogId$
   *   selectedCatalogId$ → templates (+ attributes)
   *   prefill + templates loaded → seed selectedTemplateId$
   *   selectedTemplateId$ → variants + templateFields
   *   prefill + templateFields loaded → seed dataMapping
   */
  private initCascade(): void {
    const configId$ = this.pluginConfigurationId$.pipe(
      filter((id) => !!id),
      distinctUntilChanged(),
    );

    // ── Catalogs: load when pluginConfigurationId changes ──
    configId$
      .pipe(
        takeUntil(this.destroy$),
        tap(() => this.catalogs$.next(loadingResource(this.catalogs$.getValue().data))),
        switchMap((configurationId) =>
          this.epistolaPluginService.getCatalogs(configurationId).pipe(
            map((catalogs) => successResource(catalogs.map((c) => ({ id: c.id, text: c.name })))),
            catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load catalogs'))),
          ),
        ),
      )
      .subscribe((resource) => this.catalogs$.next(resource));

    // ── Environments: load when pluginConfigurationId changes (independent) ──
    configId$
      .pipe(
        takeUntil(this.destroy$),
        tap(() => this.environments$.next(loadingResource(this.environments$.getValue().data))),
        switchMap((configurationId) =>
          this.epistolaPluginService.getEnvironments(configurationId).pipe(
            map((envs) => successResource(envs.map((e) => ({ id: e.id, text: e.name })))),
            catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load environments'))),
          ),
        ),
      )
      .subscribe((resource) => this.environments$.next(resource));

    // ── Seed selectedCatalogId$ from prefill once catalogs are loaded ──
    combineLatest([
      this.prefill$.pipe(filter((config) => !!config?.catalogId)),
      this.catalogs$.pipe(filter((c) => !c.loading && c.data.length > 0)),
    ])
      .pipe(takeUntil(this.destroy$), take(1))
      .subscribe(([config]) => {
        this.selectedCatalogId$.next(config!.catalogId);
      });

    // ── Templates: load when catalogId changes ──
    const catalogId$ = this.selectedCatalogId$.pipe(
      filter((id) => !!id),
      distinctUntilChanged(),
    );

    combineLatest([configId$, catalogId$])
      .pipe(
        takeUntil(this.destroy$),
        tap(() => this.templates$.next(loadingResource(this.templates$.getValue().data))),
        switchMap(([configurationId, catalogId]) =>
          this.epistolaPluginService.getTemplates(configurationId, catalogId).pipe(
            map((templates) => successResource(templates.map((t) => ({ id: t.id, text: t.name })))),
            catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load templates'))),
          ),
        ),
      )
      .subscribe((resource) => this.templates$.next(resource));

    // ── Attributes: load when catalogId changes ──
    combineLatest([configId$, catalogId$])
      .pipe(
        takeUntil(this.destroy$),
        switchMap(([configurationId, catalogId]) =>
          this.epistolaPluginService
            .getAttributes(configurationId, catalogId)
            .pipe(catchError(() => of([]))),
        ),
      )
      .subscribe((attributes) => {
        this.availableAttributeKeys = attributes.map((a) => a.key).sort();
        this.cdr.markForCheck();
      });

    // ── Seed selectedTemplateId$ from prefill once templates are loaded ──
    combineLatest([
      this.prefill$.pipe(filter((config) => !!config?.templateId)),
      this.templates$.pipe(filter((t) => !t.loading && t.data.length > 0)),
    ])
      .pipe(takeUntil(this.destroy$), take(1))
      .subscribe(([config]) => {
        this.selectedTemplateId$.next(config!.templateId);
      });

    // ── Variants: load when templateId changes ──
    const templateId$ = this.selectedTemplateId$.pipe(
      filter((id) => !!id),
      distinctUntilChanged(),
    );

    combineLatest([configId$, catalogId$, templateId$])
      .pipe(
        takeUntil(this.destroy$),
        tap(() => this.variants$.next(loadingResource(this.variants$.getValue().data))),
        switchMap(([configurationId, catalogId, templateId]) =>
          this.epistolaPluginService.getVariants(configurationId, templateId, catalogId).pipe(
            map((variants) =>
              successResource(
                variants.map((v) => ({
                  id: v.id,
                  text: v.name + this.formatAttributes(v.attributes),
                })),
              ),
            ),
            catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load variants'))),
          ),
        ),
      )
      .subscribe((resource) => this.variants$.next(resource));

    // ── Template fields: load when templateId changes ──
    combineLatest([configId$, catalogId$, templateId$])
      .pipe(
        takeUntil(this.destroy$),
        tap(() => {
          this.templateFields$.next(loadingResource(this.templateFields$.getValue().data));
          this.loadProcessVariables();
          this.loadVariableSuggestions();
        }),
        switchMap(([configurationId, catalogId, templateId]) =>
          this.epistolaPluginService
            .getTemplateDetails(configurationId, templateId, catalogId)
            .pipe(
              map((details) => successResource(details.fields || [])),
              catchError(() =>
                of(errorResource<TemplateField[]>([], 'Failed to load template fields')),
              ),
            ),
        ),
      )
      .subscribe((resource) => this.templateFields$.next(resource));

    // ── Seed variant + dataMapping from prefill once templateFields are loaded ──
    combineLatest([
      this.prefill$.pipe(filter((config) => !!config?.templateId)),
      this.templateFields$.pipe(filter((tf) => !tf.loading && tf.data.length > 0)),
    ])
      .pipe(takeUntil(this.destroy$), take(1))
      .subscribe(([config]) => {
        if (!config) return;

        // Apply variant prefill
        if (
          config.variantAttributes &&
          (Array.isArray(config.variantAttributes)
            ? config.variantAttributes.length > 0
            : Object.keys(config.variantAttributes).length > 0)
        ) {
          this.variantSelectionMode = 'attributes';
          if (Array.isArray(config.variantAttributes)) {
            this.variantAttributeEntries = config.variantAttributes.map((e) => ({
              key: e.key,
              value: e.value,
              required: e.required !== false,
              _expressionMode: isExpression(e.value),
            }));
          } else {
            this.variantAttributeEntries = Object.entries(config.variantAttributes as any).map(
              ([key, value]) => ({ key, value: String(value), required: true }),
            );
          }
        } else if (config.variantId) {
          this.variantSelectionMode = 'explicit';
          if (isExpression(config.variantId)) {
            this.variantIdExpressionMode = true;
            this.variantIdExpression = config.variantId;
          } else {
            this.variantIdValue = config.variantId;
          }
        }

        // Detect expression mode for filename
        if (config.filename) {
          if (isExpression(config.filename)) {
            this.filenameExpressionMode = true;
            this.filenameExpression = config.filename;
          } else {
            this.filenameValue = config.filename;
          }
        }

        // Apply dataMapping prefill (JSONata expression string)
        if (config.dataMapping) {
          const expr = typeof config.dataMapping === 'string' ? config.dataMapping : '';
          this.dataMapping$.next(expr);
        } else {
          this.cdr.detectChanges();
        }
      });
  }

  private loadExpressionFunctions(): void {
    this.epistolaPluginService
      .getExpressionFunctions()
      .pipe(
        takeUntil(this.destroy$),
        catchError(() => of([])),
      )
      .subscribe((functions) => {
        this.expressionFunctions = functions;
        this.cdr.markForCheck();
      });
  }

  private loadProcessVariables(): void {
    if (this.caseDefinitionKey) {
      this.epistolaPluginService
        .getProcessVariables(this.caseDefinitionKey)
        .pipe(
          takeUntil(this.destroy$),
          catchError(() => of([])),
        )
        .subscribe((variables) => {
          this.processVariables = variables;
          this.cdr.markForCheck();
        });
    }
  }

  private loadVariableSuggestions(): void {
    this.epistolaPluginService
      .getVariableSuggestions(
        this.caseDefinitionKey ?? undefined,
        this.caseDefinitionKey ?? undefined,
      )
      .pipe(
        takeUntil(this.destroy$),
        catchError(() => of({ doc: [], pv: [] })),
      )
      .subscribe((suggestions) => {
        this.variableSuggestions = suggestions;
        // `$case` is a valid (currently-empty) binding — keep it offered.
        this.editorContextVariables = {
          doc: suggestions.doc || [],
          pv: suggestions.pv || [],
          case: [],
        };
        this.cdr.markForCheck();
      });
  }

  private handleValid(formValue: Partial<GenerateDocumentConfig & { catalogId: string }>): void {
    const filenameProvided = this.filenameExpressionMode
      ? !!this.filenameExpression?.trim()
      : !!this.filenameValue?.trim();

    const baseComplete = !!(
      this.selectedCatalogId$.getValue() &&
      formValue?.templateId &&
      formValue?.outputFormat &&
      filenameProvided &&
      formValue?.resultProcessVariable
    );

    let variantValid = true;
    if (this.variantSelectionMode === 'attributes' && this.variantAttributeEntries.length > 0) {
      variantValid = this.variantAttributeEntries.every((e) => !!e.key && !!e.value);
    }

    const valid = baseComplete && variantValid;
    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$, this.dataMapping$])
        .pipe(take(1))
        .subscribe(([formValue, valid, dataMapping]) => {
          if (valid && formValue) {
            const catalogId = this.selectedCatalogId$.getValue();
            const templateId = formValue.templateId!;

            const config: GenerateDocumentConfig = {
              catalogId,
              templateId,
              environmentId: formValue.environmentId || undefined,
              dataMapping: dataMapping,
              outputFormat: formValue.outputFormat as 'PDF' | 'HTML',
              filename: this.filenameExpressionMode ? this.filenameExpression : this.filenameValue,
              correlationId: formValue.correlationId || undefined,
              resultProcessVariable: formValue.resultProcessVariable!,
            };

            if (this.variantSelectionMode === 'explicit') {
              config.variantId = this.variantIdExpressionMode
                ? this.variantIdExpression
                : this.variantIdValue;
            } else {
              config.variantAttributes = this.variantAttributeEntries
                .filter((e) => e.key && e.value)
                .map((e) => ({ key: e.key, value: e.value, required: e.required }));
            }

            this.validateAndEmit(config);
          }
        });
    });
  }

  /**
   * Build a JSONata validation request from the config and call the backend.
   * Only fields that are JSONata expressions get validated:
   * - dataMapping is always JSONata
   * - filename / variantId only when their `fx` toggle is on
   * - variant attribute values only when isExpression() reports true
   * On invalid response, surface errors and abort the emit.
   * If the validator endpoint itself fails (network/server), proceed with the
   * emit — the validation is a quality-of-life check, not a hard gate.
   */
  private validateAndEmit(config: GenerateDocumentConfig): void {
    const variantAttributeValues: Record<string, string> = {};
    if (config.variantAttributes) {
      for (const attr of config.variantAttributes) {
        if (isExpression(attr.value)) {
          variantAttributeValues[attr.key] = attr.value;
        }
      }
    }

    const request: ValidateJsonataRequest = {
      dataMapping: config.dataMapping || null,
      filename: this.filenameExpressionMode ? config.filename : null,
      variantId: this.variantIdExpressionMode ? config.variantId || null : null,
      variantAttributeValues:
        Object.keys(variantAttributeValues).length > 0 ? variantAttributeValues : null,
    };

    this.epistolaPluginService
      .validateJsonata(request)
      .pipe(
        take(1),
        catchError(() => of({ valid: true, errors: [] as JsonataFieldError[] })),
      )
      .subscribe((result) => {
        if (result.valid) {
          this.validationErrors$.next([]);
          this.configuration.emit(config);
        } else {
          this.validationErrors$.next(result.errors);
          this.cdr.markForCheck();
        }
      });
  }
}
