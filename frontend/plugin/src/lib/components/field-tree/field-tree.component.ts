import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {InputModule, ValuePathSelectorComponent, ValuePathSelectorPrefix} from '@valtimo/components';
import {TemplateField} from '../../models';

export type InputMode = 'browse' | 'pv' | 'expression';

/**
 * Recursive component that renders a single TemplateField node as part of a tree form.
 *
 * - SCALAR fields render as a label + input row with a 3-mode selector (browse / pv / expression)
 * - OBJECT fields render as a collapsible section with children indented inside
 * - ARRAY fields render as a collapsible section with source collection input and optional per-item field mappings
 *
 * Input modes:
 * - Browse (⊞): ValuePathSelector for doc:/case: paths
 * - PV (pv): Dropdown of discovered process variables (text fallback when none found)
 * - Expression (fx): Free-text input for manual expressions
 */
@Component({
  selector: 'epistola-field-tree',
  templateUrl: './field-tree.component.html',
  styleUrls: ['./field-tree.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    PluginTranslatePipeModule,
    InputModule,
    ValuePathSelectorComponent
  ]
})
export class FieldTreeComponent implements OnChanges {
  @Input() field!: TemplateField;
  @Input() value: any = undefined;
  @Input() pluginId!: string;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];
  @Input() disabled = false;

  @Output() valueChange = new EventEmitter<any>();

  readonly ValuePathSelectorPrefix = ValuePathSelectorPrefix;

  inputMode: InputMode = 'browse';
  expanded = false;

  /** For ARRAY fields: whether to show per-item field mapping */
  arrayPerFieldMode = false;

  /** Completeness badge for collapsed object/array sections */
  mappedCount = 0;
  totalRequired = 0;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['value'] || changes['field']) {
      this.updateCompleteness();
      // Auto-expand if there are unmapped required children
      if (!this.expanded && this.totalRequired > 0 && this.mappedCount < this.totalRequired) {
        this.expanded = true;
      }
    }
    // Detect input mode from prefill value
    if (changes['value'] && this.value != null) {
      if (this.field?.fieldType === 'SCALAR') {
        this.inputMode = this.detectInputMode(this.value);
      } else if (this.field?.fieldType === 'ARRAY') {
        const sourceValue = this.getSourceValue();
        if (sourceValue) {
          this.inputMode = this.detectInputMode(sourceValue);
        }
        // Detect per-field mode from value shape
        if (typeof this.value === 'object' && this.value !== null && '_source' in this.value) {
          this.arrayPerFieldMode = true;
        }
      }
    }
  }

  toggleExpanded(): void {
    this.expanded = !this.expanded;
  }

  setInputMode(mode: InputMode): void {
    this.inputMode = mode;
  }

  /** Handle value change from ValuePathSelector (browse mode) */
  onBrowseValueChange(newValue: string): void {
    this.emitScalarValue(newValue);
  }

  /** Handle value change from PV dropdown */
  onPvChange(newValue: string): void {
    if (newValue) {
      this.emitScalarValue('pv:' + newValue);
    } else {
      this.emitScalarValue('');
    }
  }

  /** Handle value change from text input (expression mode) */
  onExpressionValueChange(newValue: string): void {
    this.emitScalarValue(newValue);
  }

  /** Handle child value change for OBJECT fields */
  onChildChange(childName: string, childValue: any): void {
    const current = (typeof this.value === 'object' && this.value !== null) ? {...this.value} : {};
    if (childValue === undefined || childValue === null || childValue === '') {
      delete current[childName];
    } else {
      current[childName] = childValue;
    }
    this.valueChange.emit(Object.keys(current).length > 0 ? current : undefined);
  }

  /** Get the current value for a child field within an OBJECT */
  getChildValue(childName: string): any {
    if (typeof this.value === 'object' && this.value !== null) {
      return this.value[childName];
    }
    return undefined;
  }

  /** Get the string value for display (for SCALAR leaf inputs and direct array mode) */
  getStringValue(): string {
    return typeof this.value === 'string' ? this.value : '';
  }

  /** Get the PV name from a pv: prefixed value (for PV dropdown default selection) */
  getPvName(): string {
    const str = this.getStringValue();
    return str.startsWith('pv:') ? str.substring(3) : '';
  }

  // --- ARRAY-specific methods ---

  /** Get the source collection value (works for both direct string and _source format) */
  getSourceValue(): string {
    if (typeof this.value === 'string') {
      return this.value;
    }
    if (typeof this.value === 'object' && this.value !== null && '_source' in this.value) {
      return this.value['_source'] || '';
    }
    return '';
  }

  /** Get the PV name from the source value */
  getSourcePvName(): string {
    const source = this.getSourceValue();
    return source.startsWith('pv:') ? source.substring(3) : '';
  }

  toggleArrayPerFieldMode(): void {
    this.arrayPerFieldMode = !this.arrayPerFieldMode;

    if (this.arrayPerFieldMode) {
      // Switch from direct to per-field: convert string value to _source object
      const currentSource = this.getSourceValue();
      const obj: Record<string, any> = {_source: currentSource};
      this.valueChange.emit(obj);
    } else {
      // Switch from per-field to direct: extract _source as string value
      const source = this.getSourceValue();
      this.valueChange.emit(source || undefined);
    }
  }

  /** Handle source collection value change (used in ARRAY mode) */
  onSourceBrowseChange(newValue: string): void {
    this.updateSourceValue(newValue);
  }

  onSourcePvChange(newValue: string): void {
    this.updateSourceValue(newValue ? 'pv:' + newValue : '');
  }

  onSourceExpressionChange(newValue: string): void {
    this.updateSourceValue(newValue);
  }

  /** Handle per-item field mapping change */
  onItemFieldChange(childName: string, sourceFieldName: string): void {
    const current = (typeof this.value === 'object' && this.value !== null) ? {...this.value} : {_source: ''};
    if (sourceFieldName && sourceFieldName.trim().length > 0) {
      current[childName] = sourceFieldName;
    } else {
      delete current[childName];
    }
    this.valueChange.emit(current);
  }

  /** Get the current source field name mapping for a child */
  getItemFieldValue(childName: string): string {
    if (typeof this.value === 'object' && this.value !== null) {
      return this.value[childName] || '';
    }
    return '';
  }

  /** Check if the array has any children that can be mapped per-item */
  hasArrayChildren(): boolean {
    return !!(this.field?.children && this.field.children.length > 0);
  }

  private emitScalarValue(newValue: string): void {
    this.valueChange.emit(newValue || undefined);
  }

  private updateSourceValue(newValue: string): void {
    if (this.arrayPerFieldMode) {
      const current = (typeof this.value === 'object' && this.value !== null) ? {...this.value} : {};
      current['_source'] = newValue || '';
      this.valueChange.emit(current);
    } else {
      this.valueChange.emit(newValue || undefined);
    }
  }

  private updateCompleteness(): void {
    if (this.field?.fieldType === 'OBJECT' && this.field.children) {
      const stats = this.countRequiredMapped(this.field.children, this.value || {});
      this.mappedCount = stats.mapped;
      this.totalRequired = stats.total;
    } else if (this.field?.fieldType === 'ARRAY') {
      this.updateArrayCompleteness();
    }
  }

  private updateArrayCompleteness(): void {
    if (!this.field?.children || this.field.children.length === 0) {
      // No children: just check if source is set
      this.totalRequired = this.field?.required ? 1 : 0;
      this.mappedCount = this.getSourceValue() ? (this.field?.required ? 1 : 0) : 0;
      return;
    }

    if (typeof this.value === 'object' && this.value !== null && '_source' in this.value) {
      // Per-field mode: count source + required children
      let total = 0;
      let mapped = 0;

      // Source counts as 1 required
      if (this.field?.required) {
        total++;
        if (this.value['_source'] && this.value['_source'].trim().length > 0) {
          mapped++;
        }
      }

      // Count required children
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
      // Direct mode: just check if source is set
      this.totalRequired = this.field?.required ? 1 : 0;
      this.mappedCount = this.getSourceValue() ? (this.field?.required ? 1 : 0) : 0;
    }
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
        } else if (typeof val === 'object' && val !== null && '_source' in val) {
          if (typeof val['_source'] === 'string' && val['_source'].trim().length > 0) {
            mapped++;
          }
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

  private detectInputMode(value: any): InputMode {
    if (typeof value !== 'string') return 'browse';
    if (value.startsWith('doc:') || value.startsWith('case:')) return 'browse';
    if (value.startsWith('pv:')) return 'pv';
    if (value.length > 0) return 'expression';
    return 'browse';
  }

  private isResolvableValue(value: string): boolean {
    return value.startsWith('doc:') || value.startsWith('case:') || value.startsWith('pv:') || value.startsWith('template:');
  }
}
