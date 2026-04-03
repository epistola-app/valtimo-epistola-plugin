import {ChangeDetectionStrategy, Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {InputModule} from '@valtimo/components';
import {ExpressionFunctionInfo, TemplateField} from '../../models';
import {ValueInputComponent, normalizeToDots} from '../value-input/value-input.component';

@Component({
  selector: 'epistola-array-field',
  templateUrl: './array-field.component.html',
  styleUrls: ['./array-field.component.scss'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, PluginTranslatePipeModule, InputModule, ValueInputComponent]
})
export class ArrayFieldComponent implements OnChanges {
  @Input() field!: TemplateField;
  @Input() value: any = undefined;
  @Input() pluginId!: string;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];
  @Input() disabled = false;
  @Input() expressionFunctions: ExpressionFunctionInfo[] = [];

  @Output() valueChange = new EventEmitter<any>();

  expanded = false;
  arrayPerFieldMode = false;
  mappedCount = 0;
  totalRequired = 0;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['value'] || changes['field']) {
      this.updateCompleteness();
      if (!this.expanded && this.totalRequired > 0 && this.mappedCount < this.totalRequired) {
        this.expanded = true;
      }
      // Detect per-field mode from value shape
      if (changes['value'] && typeof this.value === 'object' && this.value !== null && '_source' in this.value) {
        this.arrayPerFieldMode = true;
      }
    }
  }

  toggleExpanded(): void {
    this.expanded = !this.expanded;
  }

  getSourceValue(): string {
    if (typeof this.value === 'string') {
      return normalizeToDots(this.value);
    }
    if (typeof this.value === 'object' && this.value !== null && '_source' in this.value) {
      return normalizeToDots(this.value['_source'] || '');
    }
    return '';
  }

  onSourceValueChange(newValue: string): void {
    if (this.arrayPerFieldMode) {
      const current = (typeof this.value === 'object' && this.value !== null) ? {...this.value} : {};
      current['_source'] = newValue || '';
      this.valueChange.emit(current);
    } else {
      this.valueChange.emit(newValue || undefined);
    }
  }

  toggleArrayPerFieldMode(): void {
    this.arrayPerFieldMode = !this.arrayPerFieldMode;

    if (this.arrayPerFieldMode) {
      const currentSource = this.getSourceValue();
      this.valueChange.emit({_source: currentSource});
    } else {
      const source = this.getSourceValue();
      this.valueChange.emit(source || undefined);
    }
  }

  onItemFieldChange(childName: string, sourceFieldName: string): void {
    const current = (typeof this.value === 'object' && this.value !== null) ? {...this.value} : {_source: ''};
    if (sourceFieldName && sourceFieldName.trim().length > 0) {
      current[childName] = sourceFieldName;
    } else {
      delete current[childName];
    }
    this.valueChange.emit(current);
  }

  getItemFieldValue(childName: string): string {
    if (typeof this.value === 'object' && this.value !== null) {
      return this.value[childName] || '';
    }
    return '';
  }

  hasArrayChildren(): boolean {
    return !!(this.field?.children && this.field.children.length > 0);
  }

  private updateCompleteness(): void {
    if (!this.field?.children || this.field.children.length === 0) {
      this.totalRequired = this.field?.required ? 1 : 0;
      this.mappedCount = this.getSourceValue() ? (this.field?.required ? 1 : 0) : 0;
      return;
    }

    if (typeof this.value === 'object' && this.value !== null && '_source' in this.value) {
      let total = 0;
      let mapped = 0;

      if (this.field?.required) {
        total++;
        if (this.value['_source'] && this.value['_source'].trim().length > 0) {
          mapped++;
        }
      }

      for (const child of this.field.children) {
        if (child.required) {
          total++;
          const val = this.value[child.name];
          if (typeof val === 'string' && val.trim().length > 0) {
            mapped++;
          }
        }
      }

      this.mappedCount = mapped;
      this.totalRequired = total;
    } else {
      this.totalRequired = this.field?.required ? 1 : 0;
      this.mappedCount = this.getSourceValue() ? (this.field?.required ? 1 : 0) : 0;
    }
  }
}
