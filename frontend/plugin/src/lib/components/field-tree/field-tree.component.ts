import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  forwardRef,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { TemplateField } from '../../models';
import { countRequiredMapped } from '../../utils/template-field-utils';
import { ScalarFieldComponent } from '../scalar-field/scalar-field.component';
import { ArrayFieldComponent } from '../array-field/array-field.component';

/**
 * Recursive field tree component.
 * Dispatches SCALAR and ARRAY to dedicated sub-components.
 * Handles OBJECT inline to avoid circular import issues (OBJECT children recurse back to this component).
 * Uses forwardRef(() => FieldTreeComponent) in imports to allow self-referencing in the template.
 */
@Component({
  selector: 'epistola-field-tree',
  templateUrl: './field-tree.component.html',
  styleUrls: ['./field-tree.component.scss'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    PluginTranslatePipeModule,
    ScalarFieldComponent,
    ArrayFieldComponent,
    forwardRef(() => FieldTreeComponent),
  ],
})
export class FieldTreeComponent implements OnChanges {
  @Input() field!: TemplateField;
  @Input() value: any = undefined;
  @Input() pluginId!: string;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];
  @Input() disabled = false;

  @Output() valueChange = new EventEmitter<any>();

  // OBJECT-specific state
  expanded = false;
  mappedCount = 0;
  totalRequired = 0;

  ngOnChanges(changes: SimpleChanges): void {
    if (this.field?.fieldType === 'OBJECT' && (changes['value'] || changes['field'])) {
      if (this.field.children) {
        const stats = countRequiredMapped(this.field.children, this.value || {});
        this.mappedCount = stats.mapped;
        this.totalRequired = stats.total;
      }
      if (!this.expanded && this.totalRequired > 0 && this.mappedCount < this.totalRequired) {
        this.expanded = true;
      }
    }
  }

  toggleExpanded(): void {
    this.expanded = !this.expanded;
  }

  onChildChange(childName: string, childValue: any): void {
    const current = typeof this.value === 'object' && this.value !== null ? { ...this.value } : {};
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
}
