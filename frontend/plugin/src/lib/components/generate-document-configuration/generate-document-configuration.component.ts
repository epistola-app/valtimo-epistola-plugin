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
import {catchError, filter, map, switchMap, take, takeUntil, tap} from 'rxjs/operators';
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

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly processLinkStateService: ProcessLinkStateService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.initContext();
    this.initPrefill();
    this.initPluginConfiguration();
    this.initCatalogsLoading();
    this.initTemplatesLoading();
    this.initEnvironmentsLoading();
    this.initAttributesLoading();
    this.initVariantsLoading();
    this.initTemplateFieldsLoading();
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

  private initPrefill(): void {
    if (this.prefillConfiguration$) {
      // Wait for catalogs to load before applying prefill, so v-select can match defaultSelectionId
      combineLatest([
        this.prefillConfiguration$.pipe(filter(config => !!config?.templateId)),
        this.catalogs$.pipe(filter(c => !c.loading && c.data.length > 0))
      ]).pipe(
        takeUntil(this.destroy$),
        take(1)
      ).subscribe(([config]) => {
        if (config.catalogId) {
          this.selectedCatalogId$.next(config.catalogId);
        }
        this.selectedTemplateId$.next(config.templateId);
        if (config.variantAttributes && (Array.isArray(config.variantAttributes) ? config.variantAttributes.length > 0 : Object.keys(config.variantAttributes).length > 0)) {
          this.variantSelectionMode = 'attributes';
          if (Array.isArray(config.variantAttributes)) {
            // New format: VariantAttributeEntry[]
            this.variantAttributeEntries = config.variantAttributes
              .map(e => ({key: e.key, value: e.value, required: e.required !== false}));
          } else {
            // Old format: Record<string, string> — treat all as required
            this.variantAttributeEntries = Object.entries(config.variantAttributes as any)
              .map(([key, value]) => ({key, value: String(value), required: true}));
          }
        } else if (config.variantId) {
          this.variantSelectionMode = 'explicit';
          this.selectedVariantId$.next(config.variantId);
        }
        if (config.dataMapping) {
          this.prefillDataMapping = config.dataMapping;
          this.dataMapping$.next(config.dataMapping);
        }
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

  private initCatalogsLoading(): void {
    this.pluginConfigurationId$.pipe(
      takeUntil(this.destroy$),
      filter(id => !!id),
      tap(() => this.catalogs$.next(loadingResource(this.catalogs$.getValue().data))),
      switchMap(configurationId =>
        this.epistolaPluginService.getCatalogs(configurationId).pipe(
          catchError(() => {
            this.catalogs$.next(errorResource([], 'Failed to load catalogs'));
            return of(null);
          })
        )
      ),
      filter(result => result !== null)
    ).subscribe(catalogs => {
      const items = catalogs.map(c => ({id: c.id, text: c.name}));
      this.catalogs$.next(successResource(items));
    });
  }

  private initTemplatesLoading(): void {
    combineLatest([
      this.pluginConfigurationId$,
      this.selectedCatalogId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, catalogId]) => !!configId && !!catalogId),
      tap(() => this.templates$.next(loadingResource(this.templates$.getValue().data))),
      switchMap(([configurationId, catalogId]) =>
        this.epistolaPluginService.getTemplates(configurationId, catalogId).pipe(
          catchError(() => {
            this.templates$.next(errorResource([], 'Failed to load templates'));
            return of(null);
          })
        )
      ),
      filter(result => result !== null)
    ).subscribe(templates => {
      const items = templates.map(t => ({id: t.id, text: t.name}));
      this.templates$.next(successResource(items));
    });
  }

  private initEnvironmentsLoading(): void {
    this.pluginConfigurationId$.pipe(
      takeUntil(this.destroy$),
      filter(id => !!id),
      tap(() => this.environments$.next(loadingResource(this.environments$.getValue().data))),
      switchMap(configurationId =>
        this.epistolaPluginService.getEnvironments(configurationId).pipe(
          catchError(() => {
            this.environments$.next(errorResource([], 'Failed to load environments'));
            return of(null);
          })
        )
      ),
      filter(result => result !== null)
    ).subscribe(environments => {
      this.environments$.next(successResource(environments.map(e => ({id: e.id, text: e.name}))));
    });
  }

  private initAttributesLoading(): void {
    combineLatest([
      this.pluginConfigurationId$,
      this.selectedCatalogId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, catalogId]) => !!configId && !!catalogId),
      switchMap(([configurationId, catalogId]) =>
        this.epistolaPluginService.getAttributes(configurationId, catalogId).pipe(
          catchError(() => of([]))
        )
      )
    ).subscribe(attributes => {
      this.availableAttributeKeys = attributes.map(a => a.key).sort();
      this.cdr.markForCheck();
    });
  }

  private initVariantsLoading(): void {
    combineLatest([
      this.pluginConfigurationId$,
      this.selectedCatalogId$,
      this.selectedTemplateId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, catalogId, templateId]) => !!configId && !!catalogId && !!templateId),
      tap(() => this.variants$.next(loadingResource(this.variants$.getValue().data))),
      switchMap(([configurationId, catalogId, templateId]) => {
        return this.epistolaPluginService.getVariants(configurationId, templateId, catalogId).pipe(
          catchError(() => {
            this.variants$.next(errorResource([], 'Failed to load variants'));
            return of(null);
          })
        );
      }),
      filter(result => result !== null)
    ).subscribe(variants => {
      this.variants$.next(successResource(
        variants.map(v => ({id: v.id, text: v.name + this.formatAttributes(v.attributes)}))
      ));
    });
  }

  private initTemplateFieldsLoading(): void {
    combineLatest([
      this.pluginConfigurationId$,
      this.selectedCatalogId$,
      this.selectedTemplateId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, catalogId, templateId]) => !!configId && !!catalogId && !!templateId),
      tap(() => {
        this.templateFields$.next(loadingResource(this.templateFields$.getValue().data));
        this.loadProcessVariables();
      }),
      switchMap(([configurationId, catalogId, templateId]) => {
        return this.epistolaPluginService.getTemplateDetails(configurationId, templateId, catalogId).pipe(
          catchError(() => {
            this.templateFields$.next(errorResource([], 'Failed to load template fields'));
            return of(null);
          })
        );
      }),
      filter(result => result !== null)
    ).subscribe(details => {
      this.templateFields$.next(successResource(details.fields || []));
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
