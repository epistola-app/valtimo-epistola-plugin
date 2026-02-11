import {Component, EventEmitter, Input, OnDestroy, OnInit, Output, TrackByFunction} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {
  InputModule,
  SelectItem,
  SelectModule,
  ValuePathSelectorComponent,
  ValuePathSelectorPrefix
} from '@valtimo/components';
import {BehaviorSubject, Observable, Subject} from 'rxjs';
import {filter, takeUntil} from 'rxjs/operators';
import {DataSourceType, TemplateField} from '../../models';

/**
 * Internal mapping entry with source type tracking.
 */
interface MappingRow {
  id: number;
  templateField: string;
  sourceType: DataSourceType;
  value: string; // The full value (e.g. "doc:customer.name", "pv:invoiceId", or literal)
}

/**
 * Source type options for the selector.
 */
const SOURCE_TYPE_OPTIONS: SelectItem[] = [
  {id: 'document', text: 'Document field'},
  {id: 'processVariable', text: 'Process variable'},
  {id: 'manual', text: 'Manual value'}
];

@Component({
  selector: 'epistola-data-mapping-builder',
  templateUrl: './data-mapping-builder.component.html',
  styleUrls: ['./data-mapping-builder.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    PluginTranslatePipeModule,
    InputModule,
    SelectModule,
    ValuePathSelectorComponent
  ]
})
export class DataMappingBuilderComponent implements OnInit, OnDestroy {
  @Input() pluginId!: string;
  @Input() templateFields$!: Observable<TemplateField[]>;
  @Input() disabled$!: Observable<boolean>;
  @Input() prefillMapping$!: Observable<Record<string, string>>;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];

  @Output() mappingChange = new EventEmitter<Record<string, string>>();
  @Output() requiredFieldsStatus = new EventEmitter<{mapped: number; total: number}>();

  readonly sourceTypeOptions = SOURCE_TYPE_OPTIONS;
  readonly ValuePathSelectorPrefix = ValuePathSelectorPrefix;
  mappings: MappingRow[] = [];
  templateFieldOptions$ = new BehaviorSubject<SelectItem[]>([]);
  processVariableOptions$ = new BehaviorSubject<SelectItem[]>([]);

  readonly trackByMapping: TrackByFunction<MappingRow> = (_, mapping) => mapping.id;

  private readonly destroy$ = new Subject<void>();
  private nextId = 0;
  private newMappingIds = new Set<number>();
  private allTemplateFields: TemplateField[] = [];

  ngOnInit(): void {
    this.initTemplateFieldOptions();
    this.initPrefillMapping();
    this.initProcessVariableOptions();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  addMapping(): void {
    const newId = this.nextId++;
    this.newMappingIds.add(newId);

    this.mappings = [
      ...this.mappings,
      {id: newId, templateField: '', sourceType: 'document', value: ''}
    ];
  }

  removeMapping(index: number): void {
    const mapping = this.mappings[index];
    if (mapping) {
      this.newMappingIds.delete(mapping.id);
    }
    this.mappings = this.mappings.filter((_, i) => i !== index);
    this.emitMappings();
  }

  onTemplateFieldChange(index: number, fieldId: string | number | (string | number)[]): void {
    const mapping = this.mappings[index];
    const id = Array.isArray(fieldId) ? String(fieldId[0] ?? '') : String(fieldId ?? '');

    if (!mapping) return;
    if (this.newMappingIds.has(mapping.id) && !id) return;
    if (this.newMappingIds.has(mapping.id) && id) {
      this.newMappingIds.delete(mapping.id);
    }
    if (mapping.templateField === id) return;

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, templateField: id} : m
    );
    this.emitMappings();
  }

  onSourceTypeChange(index: number, sourceType: string | number | (string | number)[]): void {
    const mapping = this.mappings[index];
    const type = (Array.isArray(sourceType) ? String(sourceType[0] ?? 'document') : String(sourceType ?? 'document')) as DataSourceType;

    if (!mapping) return;
    if (this.newMappingIds.has(mapping.id) && type === 'document') return;
    if (mapping.sourceType === type) return;

    // Clear value when switching source type
    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, sourceType: type, value: ''} : m
    );
    this.emitMappings();
  }

  /**
   * Handle value change from ValuePathSelector (doc: paths).
   */
  onDocValueChange(index: number, value: string): void {
    const mapping = this.mappings[index];
    if (!mapping) return;

    // ValuePathSelector returns "doc:path" or "case:path" already prefixed
    const fullValue = value || '';
    if (mapping.value === fullValue) return;

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, value: fullValue} : m
    );
    this.emitMappings();
  }

  /**
   * Handle process variable selection.
   */
  onPvChange(index: number, pvName: string | number | (string | number)[]): void {
    const mapping = this.mappings[index];
    const name = Array.isArray(pvName) ? String(pvName[0] ?? '') : String(pvName ?? '');

    if (!mapping) return;
    if (this.newMappingIds.has(mapping.id) && !name) return;
    if (this.newMappingIds.has(mapping.id) && name) {
      this.newMappingIds.delete(mapping.id);
    }

    const fullValue = name ? `pv:${name}` : '';
    if (mapping.value === fullValue) return;

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, value: fullValue} : m
    );
    this.emitMappings();
  }

  /**
   * Handle manual path input change.
   */
  onManualValueChange(index: number, value: string): void {
    const mapping = this.mappings[index];
    const newValue = value ?? '';

    if (!mapping) return;
    if (this.newMappingIds.has(mapping.id) && !newValue) return;
    if (mapping.value === newValue) return;

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, value: newValue} : m
    );
    this.emitMappings();
  }

  /**
   * Get the default value for ValuePathSelector (strip prefix if needed).
   */
  getDocDefaultValue(mapping: MappingRow): string {
    return mapping.value || '';
  }

  /**
   * Get the process variable name from a pv: prefixed value.
   */
  getPvDefaultValue(mapping: MappingRow): string {
    if (mapping.value.startsWith('pv:')) {
      return mapping.value.substring(3);
    }
    return mapping.value;
  }

  /**
   * Handle manual pv: input (when no suggestions are available).
   */
  onPvManualChange(index: number, value: string): void {
    const mapping = this.mappings[index];
    const name = value ?? '';

    if (!mapping) return;
    if (this.newMappingIds.has(mapping.id) && !name) return;

    const fullValue = name ? `pv:${name}` : '';
    if (mapping.value === fullValue) return;

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, value: fullValue} : m
    );
    this.emitMappings();
  }

  /**
   * Check if a template field path is a required field.
   */
  isRequiredField(path: string): boolean {
    const requiredPaths = this.collectRequiredPaths(this.allTemplateFields);
    return requiredPaths.includes(path);
  }

  /**
   * Get the display label for a template field path (with type badge).
   */
  getFieldLabel(field: TemplateField): string {
    const typeLabel = field.type || 'string';
    const requiredLabel = field.required ? ', required' : '';
    return `${field.path} (${typeLabel}${requiredLabel})`;
  }

  private initTemplateFieldOptions(): void {
    if (this.templateFields$) {
      this.templateFields$.pipe(
        takeUntil(this.destroy$)
      ).subscribe(fields => {
        this.allTemplateFields = fields;
        const options = this.flattenFieldsToOptions(fields);
        this.templateFieldOptions$.next(options);
        this.autoPopulateRequiredFields(fields);
        this.emitRequiredFieldsStatus();
      });
    }
  }

  private initProcessVariableOptions(): void {
    // Process variables will be updated externally via input binding
    this.updateProcessVariableOptions();
  }

  /**
   * Called when processVariables input changes.
   */
  ngOnChanges(): void {
    this.updateProcessVariableOptions();
  }

  private updateProcessVariableOptions(): void {
    const options: SelectItem[] = this.processVariables.map(name => ({
      id: name,
      text: name
    }));
    this.processVariableOptions$.next(options);
  }

  private initPrefillMapping(): void {
    if (this.prefillMapping$) {
      this.prefillMapping$.pipe(
        takeUntil(this.destroy$),
        filter(mapping => !!mapping && Object.keys(mapping).length > 0)
      ).subscribe(mapping => {
        this.mappings = Object.entries(mapping).map(([templateField, dataSource]) => ({
          id: this.nextId++,
          templateField,
          sourceType: this.detectSourceType(dataSource),
          value: dataSource
        }));
      });
    }
  }

  /**
   * Flatten nested template fields into a flat list of SelectItem options.
   * Uses path for identification, with type/required info in the label.
   */
  private flattenFieldsToOptions(fields: TemplateField[], result: SelectItem[] = []): SelectItem[] {
    for (const field of fields) {
      const requiredMarker = field.required ? ' *' : '';
      const typeLabel = field.fieldType !== 'SCALAR' ? ` [${field.fieldType.toLowerCase()}]` : '';
      result.push({
        id: field.path,
        text: `${field.path}${typeLabel}${requiredMarker}`
      });
      if (field.children && field.children.length > 0 && field.fieldType !== 'ARRAY') {
        // For objects, also show children as mappable fields
        this.flattenFieldsToOptions(field.children, result);
      }
    }
    return result;
  }

  /**
   * Auto-populate rows for required fields that are not yet mapped.
   */
  private autoPopulateRequiredFields(fields: TemplateField[]): void {
    const requiredPaths = this.collectRequiredPaths(fields);
    const mappedPaths = new Set(this.mappings.map(m => m.templateField));

    for (const path of requiredPaths) {
      if (!mappedPaths.has(path)) {
        this.mappings = [
          ...this.mappings,
          {id: this.nextId++, templateField: path, sourceType: 'document', value: ''}
        ];
      }
    }
  }

  /**
   * Collect all required leaf field paths from the tree.
   */
  private collectRequiredPaths(fields: TemplateField[]): string[] {
    const paths: string[] = [];
    for (const field of fields) {
      if (field.fieldType === 'SCALAR' && field.required) {
        paths.push(field.path);
      } else if (field.fieldType === 'ARRAY' && field.required) {
        paths.push(field.path);
      } else if (field.fieldType === 'OBJECT' && field.children) {
        paths.push(...this.collectRequiredPaths(field.children));
      }
    }
    return paths;
  }

  private detectSourceType(dataSource: string): DataSourceType {
    if (dataSource.startsWith('doc:') || dataSource.startsWith('case:')) {
      return 'document';
    }
    if (dataSource.startsWith('pv:')) {
      return 'processVariable';
    }
    return 'manual';
  }

  private emitMappings(): void {
    const result: Record<string, string> = {};
    for (const mapping of this.mappings) {
      if (mapping.templateField && mapping.value) {
        result[mapping.templateField] = mapping.value;
      }
    }
    this.mappingChange.emit(result);
    this.emitRequiredFieldsStatus();
  }

  private emitRequiredFieldsStatus(): void {
    const requiredPaths = this.collectRequiredPaths(this.allTemplateFields);
    const mappedPaths = new Set(
      this.mappings
        .filter(m => m.templateField && m.value)
        .map(m => m.templateField)
    );

    const mapped = requiredPaths.filter(p => mappedPaths.has(p)).length;
    this.requiredFieldsStatus.emit({mapped, total: requiredPaths.length});
  }
}
