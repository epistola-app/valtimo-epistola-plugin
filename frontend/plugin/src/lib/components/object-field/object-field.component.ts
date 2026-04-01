import {ChangeDetectionStrategy, Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {TemplateField} from '../../models';
import {countRequiredMapped} from '../../utils/template-field-utils';
import type {FieldTreeComponent as FieldTreeComponentType} from '../field-tree/field-tree.component';

// Break the circular dependency: FieldTreeComponent imports ObjectFieldComponent,
// so we defer the import using a getter that resolves at runtime.
let _FieldTreeComponent: typeof FieldTreeComponentType;
function getFieldTreeComponent(): typeof FieldTreeComponentType {
  if (!_FieldTreeComponent) {
    _FieldTreeComponent = require('../field-tree/field-tree.component').FieldTreeComponent;
  }
  return _FieldTreeComponent;
}

@Component({
  selector: 'epistola-object-field',
  templateUrl: './object-field.component.html',
  styleUrls: ['./object-field.component.scss'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
  ]
})
export class ObjectFieldComponent implements OnChanges {
  @Input() field!: TemplateField;
  @Input() value: any = undefined;
  @Input() pluginId!: string;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];
  @Input() disabled = false;

  @Output() valueChange = new EventEmitter<any>();

  expanded = false;
  mappedCount = 0;
  totalRequired = 0;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['value'] || changes['field']) {
      this.updateCompleteness();
      if (!this.expanded && this.totalRequired > 0 && this.mappedCount < this.totalRequired) {
        this.expanded = true;
      }
    }
  }

  toggleExpanded(): void {
    this.expanded = !this.expanded;
  }

  onChildChange(childName: string, childValue: any): void {
    const current = (typeof this.value === 'object' && this.value !== null) ? {...this.value} : {};
    if (childValue === undefined || childValue === null || childValue === '') {
      delete current[childName];
    } else {
      current[childName] = childValue;
    }
    this.valueChange.emit(Object.keys(current).length > 0 ? current : undefined);
  }

  getChildValue(childName: string): any {
    if (typeof this.value === 'object' && this.value !== null) {
      return this.value[childName];
    }
    return undefined;
  }

  private updateCompleteness(): void {
    if (this.field?.children) {
      const stats = countRequiredMapped(this.field.children, this.value || {});
      this.mappedCount = stats.mapped;
      this.totalRequired = stats.total;
    }
  }
}
