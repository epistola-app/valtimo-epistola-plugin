import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FunctionConfigurationComponent, PluginConfigurationData, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule, SelectItem, SelectModule} from '@valtimo/components';
import {BehaviorSubject, combineLatest, Observable, Subject, Subscription} from 'rxjs';
import {filter, map, take, takeUntil} from 'rxjs/operators';
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

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<GenerateDocumentConfig> = new EventEmitter<GenerateDocumentConfig>();

  // Template options loaded from API
  templateOptions$ = new BehaviorSubject<SelectItem[]>([]);
  templatesLoading$ = new BehaviorSubject<boolean>(false);

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

  private readonly destroy$ = new Subject<void>();
  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<Partial<GenerateDocumentConfig> | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);
  private pluginConfigurationId$ = new BehaviorSubject<string>('');

  constructor(private readonly epistolaPluginService: EpistolaPluginService) {}

  ngOnInit(): void {
    this.initPrefillDataMapping();
    this.initPluginConfiguration();
    this.initTemplatesLoading();
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

    // Update selected template if changed
    if (formValue.templateId && formValue.templateId !== this.selectedTemplateId$.getValue()) {
      this.selectedTemplateId$.next(formValue.templateId);
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

  private initPrefillDataMapping(): void {
    if (this.prefillConfiguration$) {
      this.prefillDataMapping$ = this.prefillConfiguration$.pipe(
        map(config => config?.dataMapping || {})
      );

      // Also set initial selected template
      this.prefillConfiguration$.pipe(
        takeUntil(this.destroy$),
        filter(config => !!config?.templateId)
      ).subscribe(config => {
        this.selectedTemplateId$.next(config.templateId);
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
        takeUntil(this.destroy$)
      ).subscribe({
        next: templates => {
          const options: SelectItem[] = templates.map(t => ({
            id: t.id,
            text: t.name
          }));
          this.templateOptions$.next(options);
          this.templatesLoading$.next(false);
        },
        error: () => {
          this.templatesLoading$.next(false);
          this.templateOptions$.next([]);
        }
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
        takeUntil(this.destroy$)
      ).subscribe({
        next: details => {
          this.templateFields$.next(details.fields);
          this.templateFieldsLoading$.next(false);
        },
        error: () => {
          this.templateFieldsLoading$.next(false);
          this.templateFields$.next([]);
        }
      });
    });
  }

  private handleValid(formValue: Partial<GenerateDocumentConfig>): void {
    const valid = !!(
      formValue?.templateId &&
      formValue?.outputFormat &&
      formValue?.filename &&
      formValue?.resultProcessVariable
    );
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
              dataMapping: dataMapping,
              outputFormat: formValue.outputFormat as 'PDF' | 'HTML',
              filename: formValue.filename!,
              resultProcessVariable: formValue.resultProcessVariable!
            };
            this.configuration.emit(config);
          }
        });
    });
  }
}
