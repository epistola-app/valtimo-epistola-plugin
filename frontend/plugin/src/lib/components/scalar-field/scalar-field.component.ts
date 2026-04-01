import {ChangeDetectionStrategy, Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {TemplateField} from '../../models';
import {ValueInputComponent} from '../value-input/value-input.component';

@Component({
  selector: 'epistola-scalar-field',
  templateUrl: './scalar-field.component.html',
  styleUrls: ['./scalar-field.component.scss'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, PluginTranslatePipeModule, ValueInputComponent]
})
export class ScalarFieldComponent {
  @Input() field!: TemplateField;
  @Input() value: any = undefined;
  @Input() pluginId!: string;
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];
  @Input() disabled = false;

  @Output() valueChange = new EventEmitter<any>();

  get stringValue(): string {
    return typeof this.value === 'string' ? this.value : '';
  }

  onValueChange(newValue: string): void {
    this.valueChange.emit(newValue || undefined);
  }
}
