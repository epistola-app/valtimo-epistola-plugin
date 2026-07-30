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
import {
  BehaviorSubject,
  combineLatest,
  merge,
  Observable,
  of,
  ReplaySubject,
  Subject,
  Subscription,
} from 'rxjs';
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
  GenerateDocumentConfigV1,
  GenerateDocumentConfigVersioned,
  initialResource,
  JsonataFieldError,
  loadingResource,
  successResource,
  TemplateField,
  VariableSuggestions,
} from '../../models';
import { EpistolaPluginService } from '../../services';
import { JsonataEditorComponent } from '../jsonata-editor/jsonata-editor.component';
import { ExpectedStructureComponent } from '../expected-structure/expected-structure.component';
import { MappingBuilderComponent } from '../mapping-builder/mapping-builder.component';
import { MappingPreviewComponent } from '../mapping-preview/mapping-preview.component';
import {
  isGenerateDocumentConfigValid,
  isProcessVariableNameValid,
} from './generate-document-config.util';
import {
  encodeJsonataStringLiteral,
  migrateGenerateDocumentConfig,
} from './generate-document-config-version';
import {
  buildGenerateDocumentConfig,
  buildValidateJsonataRequest,
  canRepresentExpressionAsSelection,
  createVariantAttributeEditorEntries,
  formatVariantAttributes,
  resolveExpressionSelectPrefill,
  VariantAttributeEditorEntry,
  VariantSelectionMode,
} from './generate-document-config-editor.adapter';

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

  dataMapping$ = new BehaviorSubject<string>('');
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
  filenameExpression = '';
  environmentIdExpressionMode = false;
  environmentIdExpression = '';
  /** Plain-mode environment id. Tracked outside `<v-form>` because the field has an fx wrapper. */
  environmentIdValue = '';
  correlationIdExpression = '';
  variantAttributeEntries: VariantAttributeEditorEntry[] = [];
  availableAttributeKeys: string[] = [];
  caseDefinitionKey: string | null = null;
  processVariables: string[] = [];
  expressionFunctions: ExpressionFunctionInfo[] = [];
  variableSuggestions: VariableSuggestions | null = null;
  /** Context variables for the JSONata editor's autocomplete ($doc/$pv/$case). */
  editorContextVariables: Record<string, string[]> = { doc: [], pv: [], case: [] };
  prefillDataMapping: Record<string, any> = {};
  validationErrors$ = new BehaviorSubject<JsonataFieldError[]>([]);
  configurationVersionError$ = new BehaviorSubject<string | null>(null);
  resultProcessVariableInvalid$ = new BehaviorSubject<boolean>(false);

  private readonly destroy$ = new Subject<void>();
  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<Partial<GenerateDocumentConfig> | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);
  private pluginConfigurationId$ = new BehaviorSubject<string>('');
  private readonly loadedEnvironmentOptions$ = new ReplaySubject<SelectItem[]>(1);
  private readonly loadedVariantOptions$ = new ReplaySubject<SelectItem[]>(1);

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

  onVariantIdValueChange(value: SelectedValue | undefined): void {
    // v-select is single-select here, so SelectedValue narrows to string | number;
    // our variant ids are always strings — coerce defensively.
    this.variantIdValue = value == null || Array.isArray(value) ? '' : String(value);
    this.revalidate();
  }

  onEnvironmentIdValueChange(value: SelectedValue | undefined): void {
    this.environmentIdValue = value == null || Array.isArray(value) ? '' : String(value);
    this.revalidate();
  }

  toggleVariantIdExpressionMode(): void {
    if (this.variantIdExpressionMode) {
      const selection = resolveExpressionSelectPrefill(
        this.variantIdExpression,
        this.variants$.getValue().data,
      );
      if (selection.expressionMode) return;
      this.variantIdValue = selection.value;
    } else {
      this.variantIdExpression = encodeJsonataStringLiteral(this.variantIdValue);
    }
    this.variantIdExpressionMode = !this.variantIdExpressionMode;
    this.revalidate();
  }

  toggleEnvironmentIdExpressionMode(): void {
    if (this.environmentIdExpressionMode) {
      const selection = resolveExpressionSelectPrefill(
        this.environmentIdExpression,
        this.environments$.getValue().data,
      );
      if (selection.expressionMode) return;
      this.environmentIdValue = selection.value;
    } else {
      this.environmentIdExpression = encodeJsonataStringLiteral(this.environmentIdValue);
    }
    this.environmentIdExpressionMode = !this.environmentIdExpressionMode;
    this.revalidate();
  }

  canSwitchVariantIdToDropdown(): boolean {
    return canRepresentExpressionAsSelection(
      this.variantIdExpression,
      this.variants$.getValue().data,
    );
  }

  canSwitchEnvironmentIdToDropdown(): boolean {
    return canRepresentExpressionAsSelection(
      this.environmentIdExpression,
      this.environments$.getValue().data,
    );
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

  onEnvironmentIdExpressionChange(): void {
    this.revalidate();
  }

  onCorrelationIdExpressionChange(): void {
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
      map((config) => (config ? migrateGenerateDocumentConfig(config) : null)),
      catchError((error: unknown) => {
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
    combineLatest([this.prefill$, this.loadedEnvironmentOptions$])
      .pipe(takeUntil(this.destroy$), take(1))
      .subscribe(([config, options]) => {
        const selection = resolveExpressionSelectPrefill(config?.environmentId, options);
        this.environmentIdExpressionMode = selection.expressionMode;
        this.environmentIdExpression = selection.expression;
        this.environmentIdValue = selection.value;
        this.cdr.markForCheck();
      });
  }

  private initVariantPrefill(): void {
    combineLatest([this.prefill$, this.loadedVariantOptions$])
      .pipe(takeUntil(this.destroy$), take(1))
      .subscribe(([config, options]) => {
        if (!config || (config.variantAttributes?.length ?? 0) > 0) {
          return;
        }
        const selection = resolveExpressionSelectPrefill(config.variantId, options);
        this.variantIdExpressionMode = selection.expressionMode;
        this.variantIdExpression = selection.expression;
        this.variantIdValue = selection.value;
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
      .subscribe((resource) => {
        this.environments$.next(resource);
        this.loadedEnvironmentOptions$.next(resource.data);
      });

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
                  text: v.name + formatVariantAttributes(v.attributes),
                })),
              ),
            ),
            catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load variants'))),
          ),
        ),
      )
      .subscribe((resource) => {
        this.variants$.next(resource);
        this.loadedVariantOptions$.next(resource.data);
      });

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
    this.resultProcessVariableInvalid$.next(
      !!formValue?.resultProcessVariable &&
        !isProcessVariableNameValid(formValue.resultProcessVariable),
    );

    const valid =
      !this.configurationVersionError$.getValue() &&
      isGenerateDocumentConfigValid(formValue, {
        selectedCatalogId: this.selectedCatalogId$.getValue(),
        filename: this.filenameExpression,
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
              environment: {
                expressionMode: this.environmentIdExpressionMode,
                expression: this.environmentIdExpression,
                value: this.environmentIdValue,
              },
              variantSelectionMode: this.variantSelectionMode,
              variant: {
                expressionMode: this.variantIdExpressionMode,
                expression: this.variantIdExpression,
                value: this.variantIdValue,
              },
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
