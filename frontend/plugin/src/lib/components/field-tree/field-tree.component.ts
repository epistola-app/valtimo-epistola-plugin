import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {InputModule, ValuePathSelectorComponent, ValuePathSelectorPrefix} from '@valtimo/components';
import {TemplateField} from '../../models';

/**
 * Recursive component that renders a single TemplateField node as part of a tree form.
 *
 * - SCALAR fields render as a label + input row (ValuePathSelector in browse mode, text input in fx mode)
 * - OBJECT fields render as a collapsible section with children indented inside
 * - ARRAY fields render as a collapsible section with a single "map collection to" input
 *
 * Each scalar/array field has an "fx" toggle to switch between browse mode and expression mode.
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

  fxMode = false;
  expanded = false;

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
    // Detect fx mode from prefill: if value is a non-resolvable string, switch to fx
    if (changes['value'] && this.value && this.field?.fieldType === 'SCALAR') {
      this.fxMode = typeof this.value === 'string' && !this.isResolvableValue(this.value);
    }
  }

  toggleExpanded(): void {
    this.expanded = !this.expanded;
  }

  toggleFxMode(): void {
    this.fxMode = !this.fxMode;
  }

  /** Handle value change from ValuePathSelector (browse mode) */
  onBrowseValueChange(newValue: string): void {
    this.valueChange.emit(newValue || undefined);
  }

  /** Handle value change from text input (fx mode) */
  onFxValueChange(newValue: string): void {
    this.valueChange.emit(newValue || undefined);
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

  /** Get the string value for display (for SCALAR/ARRAY leaf inputs) */
  getStringValue(): string {
    return typeof this.value === 'string' ? this.value : '';
  }

  private updateCompleteness(): void {
    if (this.field?.fieldType === 'OBJECT' && this.field.children) {
      const stats = this.countRequiredMapped(this.field.children, this.value || {});
      this.mappedCount = stats.mapped;
      this.totalRequired = stats.total;
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

  private isResolvableValue(value: string): boolean {
    return value.startsWith('doc:') || value.startsWith('case:') || value.startsWith('pv:') || value.startsWith('template:');
  }
}
