import {ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {
  FunctionConfigurationComponent,
  PluginConfigurationData,
  PluginTranslatePipeModule
} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule, SelectItem, SelectModule} from '@valtimo/components';
import {CaseManagementParams, ManagementContext} from '@valtimo/shared';
import {ProcessLinkStateService} from '@valtimo/process-link';
import {BehaviorSubject, combineLatest, merge, Observable, of, Subject, Subscription} from 'rxjs';
import {catchError, distinctUntilChanged, filter, map, shareReplay, switchMap, take, takeUntil, tap} from 'rxjs/operators';
import {AsyncResource, errorResource, GenerateDocumentConfig, initialResource, loadingResource, successResource, TemplateField} from '../../models';
import {EpistolaPluginService} from '../../services';
import {DataMappingTreeComponent} from '../data-mapping-tree/data-mapping-tree.component';

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
    DataMappingTreeComponent
  ]
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
  @Output() configuration: EventEmitter<GenerateDocumentConfig> = new EventEmitter<GenerateDocumentConfig>();

  catalogs$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  templates$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  variants$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  environments$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  templateFields$ = new BehaviorSubject<AsyncResource<TemplateField[]>>(initialResource([]));

  dataMapping$ = new BehaviorSubject<Record<string, any>>({});

  outputFormatOptions: SelectItem[] = [
    {id: 'PDF', text: 'PDF'},
    {id: 'HTML', text: 'HTML'}
  ];

  readonly selectedCatalogId$ = new BehaviorSubject<string>('');
  /** Composite ID: "catalogId/templateId" */
  readonly selectedTemplateId$ = new BehaviorSubject<string>('');
  readonly selectedVariantId$ = new BehaviorSubject<string>('');

  variantSelectionMode: VariantSelectionMode = 'explicit';
  variantAttributeEntries: {key: string; value: string; required: boolean; _customKey?: boolean}[] = [];
  availableAttributeKeys: string[] = [];
  caseDefinitionKey: string | null = null;
  processVariables: string[] = [];
  requiredFieldsStatus: {mapped: number; total: number} = {mapped: 0, total: 0};
  prefillDataMapping: Record<string, any> = {};

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
    private readonly cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.prefill$ = this.resolvePrefill$();

    this.initContext();
    this.initPluginConfiguration();
    this.initCascade();
    this.openSaveSubscription();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as Partial<GenerateDocumentConfig & { catalogId: string; templateId: string }>;
    this.formValue$.next(formValue);

    // When catalog changes, reset template and variant selection
    if (formValue.catalogId && formValue.catalogId !== this.selectedCatalogId$.getValue()) {
      this.selectedCatalogId$.next(formValue.catalogId);
      this.selectedTemplateId$.next('');
      this.selectedVariantId$.next('');
    }

    // templateId from v-select is the template ID within the selected catalog
    if (formValue.templateId && formValue.templateId !== this.selectedTemplateId$.getValue()) {
      this.selectedTemplateId$.next(formValue.templateId);
      this.selectedVariantId$.next('');
    }

    if (formValue.variantId && formValue.variantId !== this.selectedVariantId$.getValue()) {
      this.selectedVariantId$.next(formValue.variantId);
    }

    this.handleValid(formValue);
  }

  onDataMappingChange(mapping: Record<string, any>): void {
    this.dataMapping$.next(mapping);
    const currentFormValue = this.formValue$.getValue();
    if (currentFormValue) {
      this.handleValid(currentFormValue);
    }
  }

  onRequiredFieldsStatusChange(status: {mapped: number; total: number}): void {
    this.requiredFieldsStatus = status;
    const currentFormValue = this.formValue$.getValue();
    if (currentFormValue) {
      this.handleValid(currentFormValue);
    }
  }

  onVariantSelectionModeChange(mode: VariantSelectionMode): void {
    this.variantSelectionMode = mode;
    if (mode === 'attributes' && this.variantAttributeEntries.length === 0) {
      this.variantAttributeEntries = [{key: '', value: '', required: true}];
    }
    this.revalidate();
  }

  addAttributeEntry(): void {
    this.variantAttributeEntries = [...this.variantAttributeEntries, {key: '', value: '', required: true}];
    this.revalidate();
  }

  removeAttributeEntry(index: number): void {
    this.variantAttributeEntries = this.variantAttributeEntries.filter((_, i) => i !== index);
    this.revalidate();
  }

  onAttributeEntryChange(): void {
    this.revalidate();
  }

  onKeySelected(entry: {key: string; value: string; required: boolean; _customKey?: boolean}, value: string): void {
    if (value === '__custom__') {
      entry._customKey = true;
      entry.key = '';
    } else {
      entry.key = value;
    }
    this.onAttributeEntryChange();
  }

  cancelCustomKey(entry: {key: string; value: string; required: boolean; _customKey?: boolean}): void {
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
    return this.prefillConfiguration$.pipe(
      take(1),
      shareReplay(1)
    );
  }

  private initContext(): void {
    if (this.context$) {
      this.context$.pipe(
        takeUntil(this.destroy$),
        filter(([context]) => context === 'case')
      ).subscribe(([, params]) => {
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
          filter(config => !!config?.configurationId),
          map(config => config.configurationId)
        )
      );
    }

    sources.push(
      this.processLinkStateService.selectedProcessLink$.pipe(
        filter(processLink => !!processLink?.pluginConfigurationId),
        map(processLink => processLink.pluginConfigurationId!)
      )
    );

    merge(...sources).pipe(
      takeUntil(this.destroy$)
    ).subscribe(configurationId => {
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
      filter(id => !!id),
      distinctUntilChanged()
    );

    // ── Catalogs: load when pluginConfigurationId changes ──
    configId$.pipe(
      takeUntil(this.destroy$),
      tap(() => this.catalogs$.next(loadingResource(this.catalogs$.getValue().data))),
      switchMap(configurationId =>
        this.epistolaPluginService.getCatalogs(configurationId).pipe(
          map(catalogs => successResource(catalogs.map(c => ({id: c.id, text: c.name})))),
          catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load catalogs')))
        )
      )
    ).subscribe(resource => this.catalogs$.next(resource));

    // ── Environments: load when pluginConfigurationId changes (independent) ──
    configId$.pipe(
      takeUntil(this.destroy$),
      tap(() => this.environments$.next(loadingResource(this.environments$.getValue().data))),
      switchMap(configurationId =>
        this.epistolaPluginService.getEnvironments(configurationId).pipe(
          map(envs => successResource(envs.map(e => ({id: e.id, text: e.name})))),
          catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load environments')))
        )
      )
    ).subscribe(resource => this.environments$.next(resource));

    // ── Seed selectedCatalogId$ from prefill once catalogs are loaded ──
    combineLatest([
      this.prefill$.pipe(filter(config => !!config?.catalogId)),
      this.catalogs$.pipe(filter(c => !c.loading && c.data.length > 0))
    ]).pipe(
      takeUntil(this.destroy$),
      take(1)
    ).subscribe(([config]) => {
      this.selectedCatalogId$.next(config!.catalogId);
    });

    // ── Templates: load when catalogId changes ──
    const catalogId$ = this.selectedCatalogId$.pipe(
      filter(id => !!id),
      distinctUntilChanged()
    );

    combineLatest([configId$, catalogId$]).pipe(
      takeUntil(this.destroy$),
      tap(() => this.templates$.next(loadingResource(this.templates$.getValue().data))),
      switchMap(([configurationId, catalogId]) =>
        this.epistolaPluginService.getTemplates(configurationId, catalogId).pipe(
          map(templates => successResource(templates.map(t => ({id: t.id, text: t.name})))),
          catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load templates')))
        )
      )
    ).subscribe(resource => this.templates$.next(resource));

    // ── Attributes: load when catalogId changes ──
    combineLatest([configId$, catalogId$]).pipe(
      takeUntil(this.destroy$),
      switchMap(([configurationId, catalogId]) =>
        this.epistolaPluginService.getAttributes(configurationId, catalogId).pipe(
          catchError(() => of([]))
        )
      )
    ).subscribe(attributes => {
      this.availableAttributeKeys = attributes.map(a => a.key).sort();
      this.cdr.markForCheck();
    });

    // ── Seed selectedTemplateId$ from prefill once templates are loaded ──
    combineLatest([
      this.prefill$.pipe(filter(config => !!config?.templateId)),
      this.templates$.pipe(filter(t => !t.loading && t.data.length > 0))
    ]).pipe(
      takeUntil(this.destroy$),
      take(1)
    ).subscribe(([config]) => {
      this.selectedTemplateId$.next(config!.templateId);
    });

    // ── Variants: load when templateId changes ──
    const templateId$ = this.selectedTemplateId$.pipe(
      filter(id => !!id),
      distinctUntilChanged()
    );

    combineLatest([configId$, catalogId$, templateId$]).pipe(
      takeUntil(this.destroy$),
      tap(() => this.variants$.next(loadingResource(this.variants$.getValue().data))),
      switchMap(([configurationId, catalogId, templateId]) =>
        this.epistolaPluginService.getVariants(configurationId, templateId, catalogId).pipe(
          map(variants => successResource(variants.map(v => ({id: v.id, text: v.name + this.formatAttributes(v.attributes)})))),
          catchError(() => of(errorResource<SelectItem[]>([], 'Failed to load variants')))
        )
      )
    ).subscribe(resource => this.variants$.next(resource));

    // ── Template fields: load when templateId changes ──
    combineLatest([configId$, catalogId$, templateId$]).pipe(
      takeUntil(this.destroy$),
      tap(() => {
        this.templateFields$.next(loadingResource(this.templateFields$.getValue().data));
        this.loadProcessVariables();
      }),
      switchMap(([configurationId, catalogId, templateId]) =>
        this.epistolaPluginService.getTemplateDetails(configurationId, templateId, catalogId).pipe(
          map(details => successResource(details.fields || [])),
          catchError(() => of(errorResource<TemplateField[]>([], 'Failed to load template fields')))
        )
      )
    ).subscribe(resource => this.templateFields$.next(resource));

    // ── Seed variant + dataMapping from prefill once templateFields are loaded ──
    combineLatest([
      this.prefill$.pipe(filter(config => !!config?.templateId)),
      this.templateFields$.pipe(filter(tf => !tf.loading && tf.data.length > 0))
    ]).pipe(
      takeUntil(this.destroy$),
      take(1)
    ).subscribe(([config]) => {
      if (!config) return;

      // Apply variant prefill
      if (config.variantAttributes && (Array.isArray(config.variantAttributes) ? config.variantAttributes.length > 0 : Object.keys(config.variantAttributes).length > 0)) {
        this.variantSelectionMode = 'attributes';
        if (Array.isArray(config.variantAttributes)) {
          this.variantAttributeEntries = config.variantAttributes
            .map(e => ({key: e.key, value: e.value, required: e.required !== false}));
        } else {
          this.variantAttributeEntries = Object.entries(config.variantAttributes as any)
            .map(([key, value]) => ({key, value: String(value), required: true}));
        }
      } else if (config.variantId) {
        this.variantSelectionMode = 'explicit';
        this.selectedVariantId$.next(config.variantId);
      }

      // Apply dataMapping prefill — templateFields are guaranteed loaded at this point.
      // Use setTimeout to ensure the tree component exists in the DOM (after *ngIf resolves)
      // before setting the prefill, so ngOnChanges fires correctly on the child.
      if (config.dataMapping) {
        this.dataMapping$.next(config.dataMapping);
        setTimeout(() => {
          this.prefillDataMapping = {...config.dataMapping};
          this.cdr.detectChanges();
        });
      } else {
        this.cdr.detectChanges();
      }
    });
  }

  private loadProcessVariables(): void {
    if (this.caseDefinitionKey) {
      this.epistolaPluginService.getProcessVariables(this.caseDefinitionKey).pipe(
        takeUntil(this.destroy$),
        catchError(() => of([]))
      ).subscribe(variables => {
        this.processVariables = variables;
        this.cdr.markForCheck();
      });
    }
  }

  private handleValid(formValue: Partial<GenerateDocumentConfig & { catalogId: string }>): void {
    const baseComplete = !!(
      this.selectedCatalogId$.getValue() &&
      formValue?.templateId &&
      formValue?.outputFormat &&
      formValue?.filename &&
      formValue?.resultProcessVariable
    );

    let variantValid = true;
    if (this.variantSelectionMode === 'attributes' && this.variantAttributeEntries.length > 0) {
      variantValid = this.variantAttributeEntries.every(e => !!e.key && !!e.value);
    }

    const requiredFieldsMapped = this.requiredFieldsStatus.total === 0 ||
      this.requiredFieldsStatus.mapped === this.requiredFieldsStatus.total;

    const valid = baseComplete && variantValid && requiredFieldsMapped;
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
              filename: formValue.filename!,
              correlationId: formValue.correlationId || undefined,
              resultProcessVariable: formValue.resultProcessVariable!
            };

            if (this.variantSelectionMode === 'explicit') {
              config.variantId = formValue.variantId!;
            } else {
              config.variantAttributes = this.variantAttributeEntries
                .filter(e => e.key && e.value)
                .map(e => ({key: e.key, value: e.value, required: e.required}));
            }

            this.configuration.emit(config);
          }
        });
    });
  }
}
