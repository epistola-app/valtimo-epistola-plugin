import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {Observable, Subject} from 'rxjs';
import {takeUntil} from 'rxjs/operators';
import {TemplateField} from '../../models';
import {FieldTreeComponent} from '../field-tree/field-tree.component';

/**
 * Top-level wrapper that hosts FieldTreeComponent instances for each top-level template field.
 * Manages the full nested mapping object, completeness tracking, and emits mapping changes.
 */
@Component({
  selector: 'epistola-data-mapping-tree',
  templateUrl: './data-mapping-tree.component.html',
  styleUrls: ['./data-mapping-tree.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
    FieldTreeComponent
  ]
})
export class DataMappingTreeComponent implements OnInit, OnDestroy {
  @Input() pluginId!: string;
  @Input() templateFields$!: Observable<TemplateField[]>;
  @Input() prefillMapping$!: Observable<Record<string, any>>;
  @Input() disabled$!: Observable<boolean>;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];

  @Output() mappingChange = new EventEmitter<Record<string, any>>();
  @Output() requiredFieldsStatus = new EventEmitter<{mapped: number; total: number}>();

  templateFields: TemplateField[] = [];
  mapping: Record<string, any> = {};
  disabled = false;

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.templateFields$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(fields => {
      this.templateFields = fields;
      this.emitRequiredFieldsStatus();
    });

    this.prefillMapping$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(mapping => {
      if (mapping && Object.keys(mapping).length > 0) {
        this.mapping = {...mapping};
      }
      this.emitRequiredFieldsStatus();
    });

    this.disabled$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(disabled => {
      this.disabled = disabled;
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  onFieldValueChange(fieldName: string, value: any): void {
    if (value === undefined || value === null || value === '') {
      const {[fieldName]: _, ...rest} = this.mapping;
      this.mapping = rest;
    } else {
      this.mapping = {...this.mapping, [fieldName]: value};
    }
    this.mappingChange.emit(this.mapping);
    this.emitRequiredFieldsStatus();
  }

  getFieldValue(fieldName: string): any {
    return this.mapping[fieldName];
  }

  private emitRequiredFieldsStatus(): void {
    const stats = this.countRequiredMapped(this.templateFields, this.mapping);
    this.requiredFieldsStatus.emit(stats);
  }

  private countRequiredMapped(
    fields: TemplateField[],
    mapping: Record<string, any>
  ): {mapped: number; total: number} {
    let mapped = 0;
    let total = 0;
    for (const field of fields) {
      if (field.fieldType === 'SCALAR' && field.required) {
        total++;
        const val = mapping[field.name];
        if (typeof val === 'string' && val.trim().length > 0) {
          mapped++;
        }
      } else if (field.fieldType === 'ARRAY' && field.required) {
        total++;
        const val = mapping[field.name];
        if (typeof val === 'string' && val.trim().length > 0) {
          mapped++;
        }
      } else if (field.fieldType === 'OBJECT' && field.children) {
        const nested = (typeof mapping[field.name] === 'object' && mapping[field.name] !== null)
          ? mapping[field.name]
          : {};
        const childStats = this.countRequiredMapped(field.children, nested);
        mapped += childStats.mapped;
        total += childStats.total;
      }
    }
    return {mapped, total};
  }
}
