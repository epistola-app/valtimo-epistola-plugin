import {Component, EventEmitter, Input, OnDestroy, OnInit, Output, TrackByFunction} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {InputModule, SelectItem, SelectModule} from '@valtimo/components';
import {BehaviorSubject, Observable, Subject} from 'rxjs';
import {filter, takeUntil} from 'rxjs/operators';
import {TemplateField} from '../../models';

/**
 * Internal mapping entry with pre-computed prefix and path.
 * This avoids calling methods in template bindings which causes change detection issues.
 */
interface MappingRow {
  id: number; // Unique ID for trackBy
  templateField: string;
  prefix: string;
  path: string;
}

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
  mappings: MappingRow[] = [];
  templateFieldOptions$ = new BehaviorSubject<SelectItem[]>([]);

  // TrackBy function for ngFor to prevent re-rendering all items
  readonly trackByMapping: TrackByFunction<MappingRow> = (_, mapping) => mapping.id;

  private readonly destroy$ = new Subject<void>();
  private nextId = 0;
  // Track newly added mapping IDs to skip their initial v-select events
  private newMappingIds = new Set<number>();

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
    const newId = this.nextId++;
    // Track this as a new mapping to skip its initial v-select events
    this.newMappingIds.add(newId);

    this.mappings = [
      ...this.mappings,
      {id: newId, templateField: '', prefix: 'doc:', path: ''}
    ];
    // Don't emit for empty mapping - wait until user fills it
  }

  /**
   * Remove a mapping row by index.
   */
  removeMapping(index: number): void {
    const mapping = this.mappings[index];
    if (mapping) {
      this.newMappingIds.delete(mapping.id);
    }
    this.mappings = this.mappings.filter((_, i) => i !== index);
    this.emitMappings();
  }

  /**
   * Update a mapping's template field.
   */
  onTemplateFieldChange(index: number, fieldId: string | number | (string | number)[]): void {
    const mapping = this.mappings[index];
    const id = Array.isArray(fieldId) ? String(fieldId[0] ?? '') : String(fieldId ?? '');

    if (!mapping) {
      return;
    }

    // Skip if this is a newly added mapping and the value is empty (initial v-select event)
    if (this.newMappingIds.has(mapping.id) && !id) {
      return;
    }

    // Remove from new mappings set once user makes an actual selection
    if (this.newMappingIds.has(mapping.id) && id) {
      this.newMappingIds.delete(mapping.id);
    }

    // Skip if value hasn't changed
    if (mapping.templateField === id) {
      return;
    }

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, templateField: id} : m
    );
    this.emitMappings();
  }

  /**
   * Update a mapping's data source prefix.
   */
  onPrefixChange(index: number, prefix: string | number | (string | number)[]): void {
    const mapping = this.mappings[index];
    const prefixValue = Array.isArray(prefix) ? String(prefix[0] ?? 'doc:') : String(prefix ?? 'doc:');

    if (!mapping) {
      return;
    }

    // For prefix, skip only if value is the default 'doc:' on new mappings
    if (this.newMappingIds.has(mapping.id) && prefixValue === 'doc:') {
      return;
    }

    // Skip if value hasn't changed
    if (mapping.prefix === prefixValue) {
      return;
    }

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, prefix: prefixValue} : m
    );
    this.emitMappings();
  }

  /**
   * Update a mapping's data source path.
   */
  onPathChange(index: number, path: string): void {
    const mapping = this.mappings[index];
    const newPath = path ?? '';

    if (!mapping) {
      return;
    }

    // Skip if this is a newly added mapping and the path is empty (initial v-input event)
    if (this.newMappingIds.has(mapping.id) && !newPath) {
      return;
    }

    // Skip if value hasn't changed
    if (mapping.path === newPath) {
      return;
    }

    this.mappings = this.mappings.map((m, i) =>
      i === index ? {...m, path: newPath} : m
    );
    this.emitMappings();
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
          id: this.nextId++,
          templateField,
          prefix: this.extractPrefix(dataSource),
          path: this.extractPath(dataSource)
        }));
      });
    }
  }

  private extractPrefix(dataSource: string): string {
    const match = dataSource.match(/^(doc:|pv:|case:)/);
    return match ? match[1] : 'doc:';
  }

  private extractPath(dataSource: string): string {
    return dataSource.replace(/^(doc:|pv:|case:)/, '');
  }

  private emitMappings(): void {
    const result: Record<string, string> = {};
    for (const mapping of this.mappings) {
      if (mapping.templateField) {
        result[mapping.templateField] = mapping.prefix + mapping.path;
      }
    }
    this.mappingChange.emit(result);
  }
}
