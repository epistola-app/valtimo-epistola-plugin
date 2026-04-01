import {ChangeDetectionStrategy, Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {TemplateField} from '../../models';
import {countRequiredMapped} from '../../utils/template-field-utils';
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
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
    FieldTreeComponent
  ]
})
export class DataMappingTreeComponent implements OnChanges {
  @Input() pluginId!: string;
  @Input() templateFields: TemplateField[] = [];
  @Input() prefillMapping: Record<string, any> = {};
  @Input() disabled = false;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];

  @Output() mappingChange = new EventEmitter<Record<string, any>>();
  @Output() requiredFieldsStatus = new EventEmitter<{mapped: number; total: number}>();

  mapping: Record<string, any> = {};

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['prefillMapping']) {
      const mapping = this.prefillMapping;
      if (mapping && Object.keys(mapping).length > 0) {
        this.mapping = {...mapping};
      }
    }
    if (changes['templateFields'] || changes['prefillMapping']) {
      this.emitRequiredFieldsStatus();
    }
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
    const stats = countRequiredMapped(this.templateFields, this.mapping);
    this.requiredFieldsStatus.emit(stats);
  }
}
