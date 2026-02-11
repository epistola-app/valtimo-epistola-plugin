import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {
  FunctionConfigurationComponent,
  PluginConfigurationData,
  PluginTranslatePipeModule
} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule, SelectItem, SelectModule} from '@valtimo/components';
import {CaseManagementParams, ManagementContext} from '@valtimo/shared';
import {BehaviorSubject, combineLatest, Observable, of, Subject, Subscription} from 'rxjs';
import {catchError, filter, map, take, takeUntil} from 'rxjs/operators';
import {GenerateDocumentConfig, TemplateField} from '../../models';
import {EpistolaPluginService} from '../../services';
import {DataMappingBuilderComponent} from '../data-mapping-builder/data-mapping-builder.component';

@Component({
  selector: 'epistola-generate-document-configuration',
  templateUrl: './generate-document-configuration.component.html',
  styleUrls: ['./generate-document-configuration.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
    FormModule,
    InputModule,
    SelectModule,
    DataMappingBuilderComponent
  ]
})
export class GenerateDocumentConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  // Required inputs from FunctionConfigurationComponent
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<GenerateDocumentConfig>;

  // Optional inputs from FunctionConfigurationComponent
  @Input() selectedPluginConfigurationData$?: Observable<PluginConfigurationData>;
  @Input() context$?: Observable<[ManagementContext, CaseManagementParams]>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<GenerateDocumentConfig> = new EventEmitter<GenerateDocumentConfig>();

  // Template options loaded from API
  templateOptions$ = new BehaviorSubject<SelectItem[]>([]);
  templatesLoading$ = new BehaviorSubject<boolean>(false);

  // Variant options loaded based on selected template
  variantOptions$ = new BehaviorSubject<SelectItem[]>([]);
  variantsLoading$ = new BehaviorSubject<boolean>(false);

  // Environment options loaded from API
  environmentOptions$ = new BehaviorSubject<SelectItem[]>([]);
  environmentsLoading$ = new BehaviorSubject<boolean>(false);

  // Template fields for data mapping
  templateFields$ = new BehaviorSubject<TemplateField[]>([]);
  templateFieldsLoading$ = new BehaviorSubject<boolean>(false);

  // Current data mapping
  dataMapping$ = new BehaviorSubject<Record<string, string>>({});

  // Prefill data mapping observable for the builder
  prefillDataMapping$!: Observable<Record<string, string>>;

  outputFormatOptions: SelectItem[] = [
    {id: 'PDF', text: 'PDF'},
    {id: 'HTML', text: 'HTML'}
  ];

  // Show data mapping builder only when template is selected
  readonly selectedTemplateId$ = new BehaviorSubject<string>('');
  readonly selectedVariantId$ = new BehaviorSubject<string>('');

  // Case definition key from context (for ValuePathSelector)
  caseDefinitionKey: string | null = null;

  // Discovered process variables
  processVariables: string[] = [];

  // Required fields status
  requiredFieldsStatus: {mapped: number; total: number} = {mapped: 0, total: 0};

  private readonly destroy$ = new Subject<void>();
  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<Partial<GenerateDocumentConfig> | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);
  private pluginConfigurationId$ = new BehaviorSubject<string>('');

  constructor(private readonly epistolaPluginService: EpistolaPluginService) {}

  ngOnInit(): void {
    this.initContext();
    this.initPrefillDataMapping();
    this.initPluginConfiguration();
    this.initTemplatesLoading();
    this.initEnvironmentsLoading();
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
    const formValue = formOutput as unknown as Partial<GenerateDocumentConfig>;
    this.formValue$.next(formValue);

    // Update selected template if changed (also clears variant selection)
    if (formValue.templateId && formValue.templateId !== this.selectedTemplateId$.getValue()) {
      this.selectedTemplateId$.next(formValue.templateId);
      this.selectedVariantId$.next('');
    }

    // Update selected variant if changed
    if (formValue.variantId && formValue.variantId !== this.selectedVariantId$.getValue()) {
      this.selectedVariantId$.next(formValue.variantId);
    }

    this.handleValid(formValue);
  }

  onDataMappingChange(mapping: Record<string, string>): void {
    this.dataMapping$.next(mapping);
    // Re-validate when data mapping changes
    const currentFormValue = this.formValue$.getValue();
    if (currentFormValue) {
      this.handleValid(currentFormValue);
    }
  }

  onRequiredFieldsStatusChange(status: {mapped: number; total: number}): void {
    this.requiredFieldsStatus = status;
    // Re-validate when required fields status changes
    const currentFormValue = this.formValue$.getValue();
    if (currentFormValue) {
      this.handleValid(currentFormValue);
    }
  }

  private initContext(): void {
    if (this.context$) {
      this.context$.pipe(
        takeUntil(this.destroy$),
        filter(([context]) => context === 'case')
      ).subscribe(([, params]) => {
        this.caseDefinitionKey = params.caseDefinitionKey;
      });
    }
  }

  private initPrefillDataMapping(): void {
    if (this.prefillConfiguration$) {
      this.prefillDataMapping$ = this.prefillConfiguration$.pipe(
        map(config => config?.dataMapping || {})
      );

      // Also set initial selected template and variant
      this.prefillConfiguration$.pipe(
        takeUntil(this.destroy$),
        filter(config => !!config?.templateId)
      ).subscribe(config => {
        this.selectedTemplateId$.next(config.templateId);
        if (config.variantId) {
          this.selectedVariantId$.next(config.variantId);
        }
        if (config.dataMapping) {
          this.dataMapping$.next(config.dataMapping);
        }
      });
    } else {
      this.prefillDataMapping$ = new BehaviorSubject({}).asObservable();
    }
  }

  private initPluginConfiguration(): void {
    if (this.selectedPluginConfigurationData$) {
      this.selectedPluginConfigurationData$.pipe(
        takeUntil(this.destroy$),
        filter(config => !!config?.configurationId)
      ).subscribe(config => {
        this.pluginConfigurationId$.next(config.configurationId);
      });
    }
  }

  private initTemplatesLoading(): void {
    this.pluginConfigurationId$.pipe(
      takeUntil(this.destroy$),
      filter(id => !!id)
    ).subscribe(configurationId => {
      this.templatesLoading$.next(true);
      this.epistolaPluginService.getTemplates(configurationId).pipe(
        takeUntil(this.destroy$),
        catchError(() => of([]))
      ).subscribe(templates => {
        const options: SelectItem[] = templates.map(t => ({
          id: t.id,
          text: t.name
        }));
        this.templateOptions$.next(options);
        this.templatesLoading$.next(false);
      });
    });
  }

  private initEnvironmentsLoading(): void {
    this.pluginConfigurationId$.pipe(
      takeUntil(this.destroy$),
      filter(id => !!id)
    ).subscribe(configurationId => {
      this.environmentsLoading$.next(true);
      this.epistolaPluginService.getEnvironments(configurationId).pipe(
        takeUntil(this.destroy$),
        catchError(() => of([]))
      ).subscribe(environments => {
        const options: SelectItem[] = environments.map(e => ({
          id: e.id,
          text: e.name
        }));
        this.environmentOptions$.next(options);
        this.environmentsLoading$.next(false);
      });
    });
  }

  private initVariantsLoading(): void {
    combineLatest([
      this.pluginConfigurationId$,
      this.selectedTemplateId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, templateId]) => !!configId && !!templateId)
    ).subscribe(([configurationId, templateId]) => {
      this.variantsLoading$.next(true);
      this.epistolaPluginService.getVariants(configurationId, templateId).pipe(
        takeUntil(this.destroy$),
        catchError(() => of([]))
      ).subscribe(variants => {
        const options: SelectItem[] = variants.map(v => ({
          id: v.id,
          text: v.name + (v.tags.length > 0 ? ` (${v.tags.join(', ')})` : '')
        }));
        this.variantOptions$.next(options);
        this.variantsLoading$.next(false);
      });
    });
  }

  private initTemplateFieldsLoading(): void {
    combineLatest([
      this.pluginConfigurationId$,
      this.selectedTemplateId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, templateId]) => !!configId && !!templateId)
    ).subscribe(([configurationId, templateId]) => {
      this.templateFieldsLoading$.next(true);
      this.epistolaPluginService.getTemplateDetails(configurationId, templateId).pipe(
        takeUntil(this.destroy$),
        catchError(() => of({fields: []} as any))
      ).subscribe(details => {
        this.templateFields$.next(details.fields || []);
        this.templateFieldsLoading$.next(false);
      });

      // Also load process variables if we have a case context
      this.loadProcessVariables();
    });
  }

  private loadProcessVariables(): void {
    // Try to discover process variables (best-effort, may not always have context)
    if (this.caseDefinitionKey) {
      this.epistolaPluginService.getProcessVariables(this.caseDefinitionKey).pipe(
        takeUntil(this.destroy$),
        catchError(() => of([]))
      ).subscribe(variables => {
        this.processVariables = variables;
      });
    }
  }

  private handleValid(formValue: Partial<GenerateDocumentConfig>): void {
    const formComplete = !!(
      formValue?.templateId &&
      formValue?.variantId &&
      formValue?.outputFormat &&
      formValue?.filename &&
      formValue?.resultProcessVariable
    );

    // Check if all required template fields are mapped
    const requiredFieldsMapped = this.requiredFieldsStatus.total === 0 ||
      this.requiredFieldsStatus.mapped === this.requiredFieldsStatus.total;

    const valid = formComplete && requiredFieldsMapped;
    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$, this.dataMapping$])
        .pipe(take(1))
        .subscribe(([formValue, valid, dataMapping]) => {
          if (valid && formValue) {
            const config: GenerateDocumentConfig = {
              templateId: formValue.templateId!,
              variantId: formValue.variantId!,
              environmentId: formValue.environmentId || undefined,
              dataMapping: dataMapping,
              outputFormat: formValue.outputFormat as 'PDF' | 'HTML',
              filename: formValue.filename!,
              correlationId: formValue.correlationId || undefined,
              resultProcessVariable: formValue.resultProcessVariable!
            };
            this.configuration.emit(config);
          }
        });
    });
  }
}
