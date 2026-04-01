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

  templates$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  variants$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  environments$ = new BehaviorSubject<AsyncResource<SelectItem[]>>(initialResource([]));
  templateFields$ = new BehaviorSubject<AsyncResource<TemplateField[]>>(initialResource([]));

  dataMapping$ = new BehaviorSubject<Record<string, any>>({});

  outputFormatOptions: SelectItem[] = [
    {id: 'PDF', text: 'PDF'},
    {id: 'HTML', text: 'HTML'}
  ];

  readonly selectedTemplateId$ = new BehaviorSubject<string>('');
  readonly selectedVariantId$ = new BehaviorSubject<string>('');

  variantSelectionMode: VariantSelectionMode = 'explicit';
  variantAttributeEntries: {key: string; value: string}[] = [];
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
      this.variantAttributeEntries = [{key: '', value: ''}];
    }
    this.revalidate();
  }

  addAttributeEntry(): void {
    this.variantAttributeEntries = [...this.variantAttributeEntries, {key: '', value: ''}];
    this.revalidate();
  }

  removeAttributeEntry(index: number): void {
    this.variantAttributeEntries = this.variantAttributeEntries.filter((_, i) => i !== index);
    this.revalidate();
  }

  onAttributeEntryChange(): void {
    this.revalidate();
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
      this.prefillConfiguration$.pipe(
        takeUntil(this.destroy$),
        filter(config => !!config?.templateId)
      ).subscribe(config => {
        this.selectedTemplateId$.next(config.templateId);
        if (config.variantAttributes && Object.keys(config.variantAttributes).length > 0) {
          this.variantSelectionMode = 'attributes';
          this.variantAttributeEntries = Object.entries(config.variantAttributes)
            .map(([key, value]) => ({key, value}));
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

  private initTemplatesLoading(): void {
    this.pluginConfigurationId$.pipe(
      takeUntil(this.destroy$),
      filter(id => !!id),
      tap(() => this.templates$.next(loadingResource(this.templates$.getValue().data))),
      switchMap(configurationId =>
        this.epistolaPluginService.getTemplates(configurationId).pipe(
          catchError(() => {
            this.templates$.next(errorResource([], 'Failed to load templates'));
            return of(null);
          })
        )
      ),
      filter(result => result !== null)
    ).subscribe(templates => {
      this.templates$.next(successResource(templates.map(t => ({id: t.id, text: t.name}))));
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

  private initVariantsLoading(): void {
    combineLatest([
      this.pluginConfigurationId$,
      this.selectedTemplateId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, templateId]) => !!configId && !!templateId),
      tap(() => this.variants$.next(loadingResource(this.variants$.getValue().data))),
      switchMap(([configurationId, templateId]) =>
        this.epistolaPluginService.getVariants(configurationId, templateId).pipe(
          catchError(() => {
            this.variants$.next(errorResource([], 'Failed to load variants'));
            return of(null);
          })
        )
      ),
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
      this.selectedTemplateId$
    ]).pipe(
      takeUntil(this.destroy$),
      filter(([configId, templateId]) => !!configId && !!templateId),
      tap(() => {
        this.templateFields$.next(loadingResource(this.templateFields$.getValue().data));
        this.loadProcessVariables();
      }),
      switchMap(([configurationId, templateId]) =>
        this.epistolaPluginService.getTemplateDetails(configurationId, templateId).pipe(
          catchError(() => {
            this.templateFields$.next(errorResource([], 'Failed to load template fields'));
            return of(null);
          })
        )
      ),
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

  private handleValid(formValue: Partial<GenerateDocumentConfig>): void {
    const baseComplete = !!(
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
            const config: GenerateDocumentConfig = {
              templateId: formValue.templateId!,
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
              config.variantAttributes = {};
              for (const entry of this.variantAttributeEntries) {
                if (entry.key && entry.value) {
                  config.variantAttributes[entry.key] = entry.value;
                }
              }
            }

            this.configuration.emit(config);
          }
        });
    });
  }
}
