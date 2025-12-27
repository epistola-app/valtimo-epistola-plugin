import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {InputModule, SelectItem, SelectModule} from '@valtimo/components';
import {BehaviorSubject, Observable, Subject} from 'rxjs';
import {filter, takeUntil} from 'rxjs/operators';
import {DataMappingEntry, TemplateField} from '../../models';

/**
 * Data source prefix options for value resolution.
 */
const DATA_SOURCE_PREFIXES: SelectItem[] = [
  {id: 'doc:', text: 'Document (doc:)'},
  {id: 'pv:', text: 'Process Variable (pv:)'},
  {id: 'case:', text: 'Case (case:)'}
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
    SelectModule
  ]
})
export class DataMappingBuilderComponent implements OnInit, OnDestroy {
  @Input() pluginId!: string;
  @Input() templateFields$!: Observable<TemplateField[]>;
  @Input() disabled$!: Observable<boolean>;
  @Input() prefillMapping$!: Observable<Record<string, string>>;

  @Output() mappingChange = new EventEmitter<Record<string, string>>();

  readonly prefixOptions = DATA_SOURCE_PREFIXES;
  mappings: DataMappingEntry[] = [];
  templateFieldOptions$ = new BehaviorSubject<SelectItem[]>([]);

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.initTemplateFieldOptions();
    this.initPrefillMapping();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Add a new empty mapping row.
   */
  addMapping(): void {
    this.mappings = [
      ...this.mappings,
      {templateField: '', dataSource: 'doc:'}
    ];
    this.emitMappings();
  }

  /**
   * Remove a mapping row by index.
   */
  removeMapping(index: number): void {
    this.mappings = this.mappings.filter((_, i) => i !== index);
    this.emitMappings();
  }

  /**
   * Update a mapping's template field.
   * SelectedValue is string | number | Array<string | number>
   */
  onTemplateFieldChange(index: number, fieldId: string | number | (string | number)[]): void {
    if (this.mappings[index]) {
      const id = Array.isArray(fieldId) ? String(fieldId[0]) : String(fieldId);
      this.mappings[index] = {...this.mappings[index], templateField: id || ''};
      this.emitMappings();
    }
  }

  /**
   * Update a mapping's data source prefix.
   * SelectedValue is string | number | Array<string | number>
   */
  onPrefixChange(index: number, prefix: string | number | (string | number)[]): void {
    if (this.mappings[index]) {
      const prefixValue = Array.isArray(prefix) ? String(prefix[0]) : String(prefix);
      const currentSource = this.mappings[index].dataSource;
      const currentPath = this.extractPath(currentSource);
      this.mappings[index] = {...this.mappings[index], dataSource: (prefixValue || 'doc:') + currentPath};
      this.emitMappings();
    }
  }

  /**
   * Update a mapping's data source path.
   */
  onPathChange(index: number, path: string): void {
    if (this.mappings[index]) {
      const currentSource = this.mappings[index].dataSource;
      const currentPrefix = this.extractPrefix(currentSource);
      this.mappings[index] = {...this.mappings[index], dataSource: currentPrefix + path};
      this.emitMappings();
    }
  }

  /**
   * Extract the prefix from a data source value.
   */
  extractPrefix(dataSource: string): string {
    const match = dataSource.match(/^(doc:|pv:|case:)/);
    return match ? match[1] : 'doc:';
  }

  /**
   * Extract the path from a data source value.
   */
  extractPath(dataSource: string): string {
    return dataSource.replace(/^(doc:|pv:|case:)/, '');
  }

  /**
   * Check if a template field is required.
   */
  isFieldRequired(fieldName: string, templateFields: TemplateField[]): boolean {
    const field = templateFields.find(f => f.name === fieldName);
    return field?.required ?? false;
  }

  private initTemplateFieldOptions(): void {
    if (this.templateFields$) {
      this.templateFields$.pipe(
        takeUntil(this.destroy$)
      ).subscribe(fields => {
        const options: SelectItem[] = fields.map(field => ({
          id: field.name,
          text: field.required ? `${field.name} *` : field.name
        }));
        this.templateFieldOptions$.next(options);
      });
    }
  }

  private initPrefillMapping(): void {
    if (this.prefillMapping$) {
      this.prefillMapping$.pipe(
        takeUntil(this.destroy$),
        filter(mapping => !!mapping && Object.keys(mapping).length > 0)
      ).subscribe(mapping => {
        this.mappings = Object.entries(mapping).map(([templateField, dataSource]) => ({
          templateField,
          dataSource
        }));
      });
    }
  }

  private emitMappings(): void {
    const result: Record<string, string> = {};
    for (const mapping of this.mappings) {
      if (mapping.templateField && mapping.dataSource) {
        result[mapping.templateField] = mapping.dataSource;
      }
    }
    this.mappingChange.emit(result);
  }
}
