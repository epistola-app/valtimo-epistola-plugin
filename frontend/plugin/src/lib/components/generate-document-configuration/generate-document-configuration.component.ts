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
  FunctionConfigurationData,
  PluginConfigurationData,
  PluginTranslatePipeModule,
} from '@valtimo/plugin';
import { FormModule, FormOutput, InputModule, SelectItem, SelectModule } from '@valtimo/components';
import { CaseManagementParams, ManagementContext } from '@valtimo/shared';
import { ProcessLinkStateService } from '@valtimo/process-link';
import { BehaviorSubject, combineLatest, merge, Observable, of, Subject, Subscription } from 'rxjs';
import {
  catchError,
  distinctUntilChanged,
  filter,
  map,
  shareReplay,
  startWith,
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
  GenerateDocumentConfigV1,
  GenerateDocumentConfigVersioned,
  initialResource,
  JsonSchema,
  JsonataFieldError,
  loadingResource,
  SimpleMappingSupport,
  successResource,
  TemplateDetails,
  TemplateField,
} from '../../models';
import { FULL_SIMPLE_MAPPING_SUPPORT, supportsSimpleMapping } from '../../schema/template-schema';
import { isBuilderCompatible } from '../../utils/jsonata-converter';
import { EpistolaPluginService } from '../../services';
import { JsonataEditorComponent } from '../jsonata-editor/jsonata-editor.component';
import { ExpectedStructureComponent } from '../expected-structure/expected-structure.component';
import { MappingBuilderComponent } from '../mapping-builder/mapping-builder.component';
import { MappingPreviewComponent } from '../mapping-preview/mapping-preview.component';
import { SmartExpressionEditorComponent } from '../smart-expression-editor/smart-expression-editor.component';
import {
  analyzeDataMappingCompleteness,
  DataMappingCompleteness,
  isGenerateDocumentConfigValid,
  isProcessVariableNameValid,
} from './generate-document-config.util';
import {
  DEFAULT_GENERATE_DOCUMENT_DATA_MAPPING,
  isLegacyGenerateDocumentConfig,
  migrateGenerateDocumentConfig,
} from './generate-document-config-version';
import {
  buildGenerateDocumentConfig,
  buildValidateJsonataRequest,
  createVariantAttributeEditorEntries,
  formatVariantAttributes,
  VariantAttributeEditorEntry,
  VariantSelectionMode,
} from './generate-document-config-editor.adapter';

/**
 * The id a select reports, or '' for no selection. Carbon's combo box reports a cleared
 * single selection as an empty array before v-select settles on '', and that array must not
 * be taken for an id: it would be requested as the template `''`.
 */
function selectionId(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

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
    SmartExpressionEditorComponent,
  ],
})
export class GenerateDocumentConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<GenerateDocumentConfigVersioned>;
  @Input() selectedPluginConfigurationData$?: Observable<PluginConfigurationData>;
  @Input() context$?: Observable<[ManagementContext, CaseManagementParams]>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  // Framework's FunctionConfigurationData (index type) to satisfy the invariant
  // EventEmitter contract under strict mode; emitted values remain the typed config.
  @Output() configuration: EventEmitter<FunctionConfigurationData> =
    new EventEmitter<FunctionConfigurationData>();

  catalogs$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  templates$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  variants$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  environments$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  templateFields$ = new BehaviorSubject<AsyncResource<TemplateField[]>>(initialResource([]));
  templateSchema$ = new BehaviorSubject<JsonSchema | boolean | null>(null);
  simpleMappingSupport$ = new BehaviorSubject<SimpleMappingSupport>(FULL_SIMPLE_MAPPING_SUPPORT);

  dataMapping$ = new BehaviorSubject<string>(DEFAULT_GENERATE_DOCUMENT_DATA_MAPPING);
  mappingMode: 'simple' | 'advanced' = 'simple';
  toolsCollapsed = true;
  activeToolTab: 'schema' | 'preview' = 'preview';

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

  variantSelectionMode: VariantSelectionMode = 'explicit';
  variantIdExpression = '';
  filenameExpression = '';
  environmentIdExpression = '';
  correlationIdExpression = '';
  variantAttributeEntries: VariantAttributeEditorEntry[] = [];
  availableAttributeKeys: string[] = [];
  caseDefinitionKey: string | null = null;
  expressionFunctions: ExpressionFunctionInfo[] = [];
  /** Context variables for the JSONata editor's autocomplete ($doc/$pv/$case). */
  editorContextVariables: Record<string, string[]> = { doc: [], pv: [], case: [] };
  prefillDataMapping: Record<string, any> = {};
  validationErrors$ = new BehaviorSubject<JsonataFieldError[]>([]);
  configurationVersionError$ = new BehaviorSubject<string | null>(null);
  legacyConfigurationLoaded$ = new BehaviorSubject<boolean>(false);
  resultProcessVariableInvalid$ = new BehaviorSubject<boolean>(false);
  dataMappingCompleteness$ = new BehaviorSubject<DataMappingCompleteness>({
    staticallyAnalyzable: true,
    mappedRequiredFields: 0,
    totalRequiredFields: 0,
    missingRequiredFields: [],
  });

  private readonly destroy$ = new Subject<void>();
  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<Partial<GenerateDocumentConfig> | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);
  private pluginConfigurationId$ = new BehaviorSubject<string>('');
  private readonly expressionValidity = new Map<string, boolean>();
  private nextAttributeEditorId = 0;
  private templateFieldsLoadedForTemplateId: string | null = null;
  private mappingModeForcedBySchema = false;
  /** The catalog and template ids the form last reported, to tell a clear from a load. */
  private reportedCatalogId = '';
  private reportedTemplateId = '';

  /** Resolves once with the prefill config (or empty config if none). */
  private prefill$!: Observable<GenerateDocumentConfigV1 | null>;
  effectivePrefill$!: Observable<GenerateDocumentConfigV1 | null>;

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly processLinkStateService: ProcessLinkStateService,
    private readonly cdr: ChangeDetectorRef,
  ) {}

  ngOnInit(): void {
    this.prefill$ = this.resolvePrefill$();
    this.effectivePrefill$ = this.prefill$;

    this.initContext();
    this.initPluginConfiguration();
    this.initCascade();
    this.initEnvironmentPrefill();
    this.initVariantPrefill();
    this.initCorrelationIdPrefill();
    this.loadExpressionFunctions();
    this.openSaveSubscription();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const reported = formOutput as unknown as Partial<GenerateDocumentConfig> & {
      catalogId?: unknown;
      templateId?: unknown;
    };
    const catalogId = selectionId(reported.catalogId);
    const templateId = selectionId(reported.templateId);

    // An empty value is a clear only when the form last reported a selection for that
    // control. While a saved configuration loads, the selections are seeded before the
    // selects show them, and an emission from that window must not wipe them.
    const catalogCleared = !catalogId && !!this.reportedCatalogId;
    const templateCleared = !templateId && !!this.reportedTemplateId;
    this.reportedCatalogId = catalogId;
    this.reportedTemplateId = templateId;

    if ((catalogId && catalogId !== this.selectedCatalogId$.getValue()) || catalogCleared) {
      this.changeCatalog(catalogId);
    } else if (
      (templateId && templateId !== this.selectedTemplateId$.getValue()) ||
      templateCleared
    ) {
      this.changeTemplate(templateId);
    }

    // The selection the component acts on, which after a catalog change is no longer the
    // template the form still reports.
    const formValue = {
      ...reported,
      catalogId: this.selectedCatalogId$.getValue(),
      templateId: this.selectedTemplateId$.getValue(),
    };
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  onDataMappingChange(expression: string): void {
    this.dataMapping$.next(expression);
    this.revalidate();
  }

  onDataMappingValidityChange(valid: boolean): void {
    this.onExpressionValidityChange('dataMapping', valid);
  }

  onMappingModeChange(mode: 'simple' | 'advanced'): void {
    if (mode === 'simple' && !this.canUseSimpleMapping()) {
      return;
    }
    this.mappingMode = mode;
    this.mappingModeForcedBySchema = false;
    this.expressionValidity.delete('dataMapping');
    this.revalidate();
  }

  canUseSimpleMapping(): boolean {
    return (
      supportsSimpleMapping(this.simpleMappingSupport$.getValue()) &&
      isBuilderCompatible(this.dataMapping$.getValue())
    );
  }

  onExpressionValidityChange(field: string, valid: boolean): void {
    this.expressionValidity.set(field, valid);
    this.revalidate();
  }

  onVariantSelectionModeChange(mode: VariantSelectionMode): void {
    this.variantSelectionMode = mode;
    if (mode === 'explicit') {
      for (const key of [...this.expressionValidity.keys()]) {
        if (key.startsWith('variantAttribute:')) {
          this.expressionValidity.delete(key);
        }
      }
    } else {
      this.expressionValidity.delete('variantId');
    }
    if (mode === 'attributes' && this.variantAttributeEntries.length === 0) {
      this.variantAttributeEntries = [
        {
          key: '',
          value: '',
          required: true,
          _editorId: this.newAttributeEditorId(),
        },
      ];
    }
    this.revalidate();
  }

  addAttributeEntry(): void {
    this.variantAttributeEntries = [
      ...this.variantAttributeEntries,
      {
        key: '',
        value: '',
        required: true,
        _editorId: this.newAttributeEditorId(),
      },
    ];
    this.revalidate();
  }

  removeAttributeEntry(index: number): void {
    const removed = this.variantAttributeEntries[index];
    if (removed?._editorId) {
      this.expressionValidity.delete(`variantAttribute:${removed._editorId}`);
    }
    this.variantAttributeEntries = this.variantAttributeEntries.filter((_, i) => i !== index);
    this.revalidate();
  }

  onAttributeEntryChange(): void {
    this.revalidate();
  }

  onAttributeExpressionChange(entry: VariantAttributeEditorEntry, value: string): void {
    entry.value = value;
    this.revalidate();
  }

  onVariantIdExpressionChange(value: string): void {
    this.variantIdExpression = value;
    this.revalidate();
  }

  onFilenameExpressionChange(value: string): void {
    this.filenameExpression = value;
    this.revalidate();
  }

  onEnvironmentIdExpressionChange(value: string): void {
    this.environmentIdExpression = value;
    this.revalidate();
  }

  onCorrelationIdExpressionChange(value: string): void {
    this.correlationIdExpression = value;
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

  /**
   * A new catalog invalidates the template and everything chosen for it. The template is
   * cleared before the catalog changes, so the template loaders never see the old template
   * paired with the new catalog. The clear subject also resets the template select, which
   * otherwise keeps showing the previous id.
   */
  private changeCatalog(catalogId: string): void {
    this.resetTemplateSelections();
    this.selectedTemplateId$.next('');
    this.clearTemplateId$.next();
    this.selectedCatalogId$.next(catalogId);
  }

  /** A new or cleared template invalidates everything chosen for the previous one. */
  private changeTemplate(templateId: string): void {
    this.resetTemplateSelections();
    this.selectedTemplateId$.next(templateId);
  }

  /**
   * Returns what was chosen for a specific template — the variant selection and the data
   * mapping — to its initial state. Settings that do not depend on the template (filename,
   * environment, correlation id, result variable) are kept. Never called while a saved
   * configuration loads: that seeds the selections directly.
   */
  private resetTemplateSelections(): void {
    this.variantSelectionMode = 'explicit';
    this.variantIdExpression = '';
    this.variantAttributeEntries = [];
    for (const key of [...this.expressionValidity.keys()]) {
      if (key === 'variantId' || key === 'dataMapping' || key.startsWith('variantAttribute:')) {
        this.expressionValidity.delete(key);
      }
    }
    this.dataMapping$.next(DEFAULT_GENERATE_DOCUMENT_DATA_MAPPING);
    this.mappingMode = 'simple';
    this.mappingModeForcedBySchema = false;
  }

  private revalidate(): void {
    const currentFormValue = this.formValue$.getValue();
    if (currentFormValue) {
      this.handleValid(currentFormValue);
    }
  }

  private newAttributeEditorId(): string {
    return `new-${this.nextAttributeEditorId++}`;
  }

  /**
   * Creates a shared observable that resolves once with the prefill config
   * (or null if no prefill is provided). This is used to seed the cascade
   * with initial selection values before any loading starts.
   */
  private resolvePrefill$(): Observable<GenerateDocumentConfigV1 | null> {
    if (!this.prefillConfiguration$) {
      return of(null).pipe(shareReplay(1));
    }
    return this.prefillConfiguration$.pipe(
      take(1),
      map((config) => {
        if (!config) {
          return null;
        }
        const migrated = migrateGenerateDocumentConfig(config);
        this.legacyConfigurationLoaded$.next(isLegacyGenerateDocumentConfig(config));
        return migrated;
      }),
      catchError((error: unknown) => {
        this.legacyConfigurationLoaded$.next(false);
        this.configurationVersionError$.next(
          error instanceof Error ? error.message : 'Invalid generate-document configuration.',
        );
        this.valid$.next(false);
        this.valid.emit(false);
        return of(null);
      }),
      shareReplay(1),
    );
  }

  private initEnvironmentPrefill(): void {
    this.prefill$.pipe(takeUntil(this.destroy$), take(1)).subscribe((config) => {
      this.environmentIdExpression = config?.environmentId || '';
      this.cdr.markForCheck();
    });
  }

  private initVariantPrefill(): void {
    this.prefill$.pipe(takeUntil(this.destroy$), take(1)).subscribe((config) => {
      if (!config || (config.variantAttributes?.length ?? 0) > 0) {
        return;
      }
      this.variantIdExpression = config.variantId || '';
      this.cdr.markForCheck();
    });
  }

  private initCorrelationIdPrefill(): void {
    this.prefill$.pipe(takeUntil(this.destroy$), take(1)).subscribe((config) => {
      if (config?.correlationId) {
        this.correlationIdExpression = config.correlationId;
        this.cdr.markForCheck();
      }
    });
  }

  private initContext(): void {
    const caseDefinitionKey$ = this.context$
      ? this.context$.pipe(
          map(([context, params]) => (context === 'case' ? params.caseDefinitionKey : null)),
          startWith(null),
        )
      : of(null);
    const processDefinitionKey$ = this.processLinkStateService.modalParams$.pipe(
      map((params) => params?.processDefinitionKey || null),
      startWith(null),
    );

    combineLatest([caseDefinitionKey$, processDefinitionKey$])
      .pipe(
        takeUntil(this.destroy$),
        distinctUntilChanged(
          ([previousCase, previousProcess], [nextCase, nextProcess]) =>
            previousCase === nextCase && previousProcess === nextProcess,
        ),
        tap(([caseDefinitionKey]) => {
          this.caseDefinitionKey = caseDefinitionKey;
        }),
        filter(
          ([caseDefinitionKey, processDefinitionKey]) =>
            !!caseDefinitionKey || !!processDefinitionKey,
        ),
        switchMap(([caseDefinitionKey, processDefinitionKey]) =>
          this.epistolaPluginService
            .getVariableSuggestions(
              caseDefinitionKey ?? undefined,
              processDefinitionKey ?? undefined,
            )
            .pipe(catchError(() => of({ doc: [], pv: [] }))),
        ),
      )
      .subscribe((suggestions) => {
        this.editorContextVariables = {
          doc: suggestions.doc || [],
          pv: suggestions.pv || [],
          case: [],
        };
        this.cdr.markForCheck();
      });
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

    // An empty id is no selection: it resets what depends on it, and nothing is fetched.
    const catalogId$ = this.selectedCatalogId$.pipe(distinctUntilChanged());

    // ── Templates: load when catalogId changes ──
    combineLatest([configId$, catalogId$])
      .pipe(
        takeUntil(this.destroy$),
        switchMap(([configurationId, catalogId]) =>
          !catalogId
            ? of(initialResource<SelectItem[]>([]))
            : this.epistolaPluginService.getTemplates(configurationId, catalogId).pipe(
                map((templates) =>
                  successResource(templates.map((t) => ({ id: t.id, text: t.name }))),
                ),
                catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load templates'))),
                startWith(loadingResource<SelectItem[]>([])),
              ),
        ),
      )
      .subscribe((resource) => this.templates$.next(resource));

    // ── Attributes: load when catalogId changes ──
    combineLatest([configId$, catalogId$])
      .pipe(
        takeUntil(this.destroy$),
        switchMap(([configurationId, catalogId]) =>
          !catalogId
            ? of([])
            : this.epistolaPluginService
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

    // The template loaders key on catalog and template together. A template id means
    // nothing outside its catalog, and changeCatalog() clears the template before the
    // catalog moves, so no emission pairs a template with a catalog it is not in.
    const templateSelection$ = combineLatest([
      configId$,
      catalogId$,
      this.selectedTemplateId$.pipe(distinctUntilChanged()),
    ]);

    // ── Variants: load when the template selection changes ──
    templateSelection$
      .pipe(
        takeUntil(this.destroy$),
        switchMap(([configurationId, catalogId, templateId]) =>
          !catalogId || !templateId
            ? of(initialResource<SelectItem[]>([]))
            : this.epistolaPluginService.getVariants(configurationId, templateId, catalogId).pipe(
                map((variants) =>
                  successResource(
                    variants.map((v) => ({
                      id: v.id,
                      text: v.name + formatVariantAttributes(v.attributes),
                    })),
                  ),
                ),
                catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load variants'))),
                startWith(loadingResource<SelectItem[]>([])),
              ),
        ),
      )
      .subscribe((resource) => this.variants$.next(resource));

    // ── Template fields: load when the template selection changes ──
    templateSelection$
      .pipe(
        takeUntil(this.destroy$),
        tap(() => {
          this.templateFieldsLoadedForTemplateId = null;
          this.templateSchema$.next(null);
          this.simpleMappingSupport$.next(FULL_SIMPLE_MAPPING_SUPPORT);
        }),
        switchMap(([configurationId, catalogId, templateId]) =>
          !catalogId || !templateId
            ? of(initialResource<TemplateField[]>([]))
            : this.epistolaPluginService
                .getTemplateDetails(configurationId, templateId, catalogId)
                .pipe(
                  tap((details) => this.applyTemplateSchemaDetails(details)),
                  map((details) => successResource(details.fields || [])),
                  catchError(() =>
                    of(errorResource<TemplateField[]>([], 'Failed to load template fields')),
                  ),
                  startWith(loadingResource<TemplateField[]>([])),
                ),
        ),
      )
      .subscribe((resource) => {
        this.templateFields$.next(resource);
        const templateId = this.selectedTemplateId$.getValue();
        this.templateFieldsLoadedForTemplateId =
          resource.loading || resource.error || !templateId ? null : templateId;
        this.revalidate();
        this.cdr.markForCheck();
      });

    // ── Seed expression-capable fields from the locally migrated prefill ──
    this.prefill$
      .pipe(
        filter((config) => !!config?.templateId),
        takeUntil(this.destroy$),
        take(1),
      )
      .subscribe((config) => {
        if (!config) return;

        // Apply variant prefill
        if (config.variantAttributes && config.variantAttributes.length > 0) {
          this.variantSelectionMode = 'attributes';
          this.variantAttributeEntries = createVariantAttributeEditorEntries(
            config.variantAttributes,
          );
        }

        // Filename is always represented directly as JSONata.
        if (config.filename) {
          this.filenameExpression = config.filename;
        }

        // Apply dataMapping prefill (JSONata expression string)
        if (config.dataMapping) {
          const expr = typeof config.dataMapping === 'string' ? config.dataMapping : '';
          this.dataMapping$.next(expr);
          if (!isBuilderCompatible(expr)) {
            this.mappingMode = 'advanced';
          }
        } else {
          this.cdr.detectChanges();
        }
      });
  }

  private applyTemplateSchemaDetails(details: TemplateDetails): void {
    this.templateSchema$.next(details.schema ?? null);
    const support = details.simpleMappingSupport ?? FULL_SIMPLE_MAPPING_SUPPORT;
    this.simpleMappingSupport$.next(support);

    if (support.level === 'UNSUPPORTED') {
      this.mappingMode = 'advanced';
      this.mappingModeForcedBySchema = true;
      return;
    }
    if (this.mappingModeForcedBySchema && isBuilderCompatible(this.dataMapping$.getValue())) {
      this.mappingMode = 'simple';
      this.mappingModeForcedBySchema = false;
    }
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

  private handleValid(formValue: Partial<GenerateDocumentConfig & { catalogId: string }>): void {
    this.resultProcessVariableInvalid$.next(
      !!formValue?.resultProcessVariable &&
        !isProcessVariableNameValid(formValue.resultProcessVariable),
    );

    const dataMapping = this.dataMapping$.getValue();
    const templateFieldsResource = this.templateFields$.getValue();
    const templateFieldsReady =
      !templateFieldsResource.loading &&
      !templateFieldsResource.error &&
      this.templateFieldsLoadedForTemplateId === formValue.templateId;
    const templateFields = templateFieldsReady ? templateFieldsResource.data : [];
    this.dataMappingCompleteness$.next(analyzeDataMappingCompleteness(dataMapping, templateFields));

    const valid =
      !this.configurationVersionError$.getValue() &&
      [...this.expressionValidity.values()].every(Boolean) &&
      isGenerateDocumentConfigValid(formValue, {
        selectedCatalogId: this.selectedCatalogId$.getValue(),
        dataMapping,
        filename: this.filenameExpression,
        templateFields,
        templateFieldsReady,
        variantSelectionMode: this.variantSelectionMode,
        variantAttributeEntries: this.variantAttributeEntries,
      });
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

            const config = buildGenerateDocumentConfig({
              catalogId,
              templateId,
              dataMapping,
              filenameExpression: this.filenameExpression,
              correlationIdExpression: this.correlationIdExpression,
              resultProcessVariable: formValue.resultProcessVariable!,
              environmentExpression: this.environmentIdExpression,
              variantSelectionMode: this.variantSelectionMode,
              variantExpression: this.variantIdExpression,
              variantAttributes: this.variantAttributeEntries,
            });

            this.validateAndEmit(config);
          }
        });
    });
  }

  /**
   * Build a JSONata validation request from the config and call the backend.
   * Every expression-capable v1 field contains JSONata, including encoded literals.
   * On invalid response, surface errors and abort the emit.
   * If the validator endpoint itself fails (network/server), proceed with the
   * emit — the validation is a quality-of-life check, not a hard gate.
   */
  private validateAndEmit(config: GenerateDocumentConfig): void {
    this.epistolaPluginService
      .validateJsonata(buildValidateJsonataRequest(config))
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
