import {ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {PluginTranslatePipeModule} from '@valtimo/plugin';
import {InputModule, ValuePathSelectorComponent, ValuePathSelectorPrefix} from '@valtimo/components';

export type InputMode = 'browse' | 'pv' | 'expression';

/**
 * Reusable 3-mode input (browse / pv / expression) for value resolver expressions.
 * Used by both ScalarFieldComponent and ArrayFieldComponent for source mapping.
 */
@Component({
  selector: 'epistola-value-input',
  templateUrl: './value-input.component.html',
  styleUrls: ['./value-input.component.scss'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    FormsModule,
    PluginTranslatePipeModule,
    InputModule,
    ValuePathSelectorComponent
  ]
})
export class ValueInputComponent implements OnChanges {
  @Input() name = '';
  @Input() value = '';
  @Input() pluginId = '';
  @Input() caseDefinitionKey: string | null = null;
  @Input() processVariables: string[] = [];
  @Input() disabled = false;
  @Input() placeholder = 'e.g. pv:variableName or doc:path.to.field';

  @Output() valueChange = new EventEmitter<string>();

  readonly ValuePathSelectorPrefix = ValuePathSelectorPrefix;

  inputMode: InputMode = 'browse';
  selectedPv = '';
  browseDefault = '';

  constructor(private readonly cdr: ChangeDetectorRef) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['value']) {
      this.inputMode = this.detectInputMode(this.value);
      this.browseDefault = normalizeToDots(this.value);
      this.selectedPv = extractPvName(this.value);
    }
    if (changes['processVariables']) {
      this.cdr.markForCheck();
    }
  }

  setInputMode(mode: InputMode): void {
    this.inputMode = mode;
  }

  onBrowseValueChange(newValue: string): void {
    this.valueChange.emit(normalizeToDots(newValue));
  }

  onPvChange(newValue: string): void {
    this.selectedPv = newValue;
    this.valueChange.emit(newValue ? 'pv:' + newValue : '');
  }

  onExpressionValueChange(newValue: string): void {
    this.valueChange.emit(newValue);
  }

  private detectInputMode(value: string): InputMode {
    if (!value) return 'browse';
    if (value.startsWith('doc:') || value.startsWith('case:')) return 'browse';
    if (value.startsWith('pv:')) return 'pv';
    if (value.length > 0) return 'expression';
    return 'browse';
  }
}

/** Convert slash-notation paths (e.g. doc:/a/b) to dot notation (doc:a.b). */
export function normalizeToDots(value: string): string {
  if (typeof value !== 'string') return value;
  const colonIndex = value.indexOf(':');
  if (colonIndex < 0) return value;
  const prefix = value.substring(0, colonIndex);
  const path = value.substring(colonIndex + 1);
  if (!path.includes('/')) return value;
  const normalized = path.split('/').filter(p => p.length > 0).join('.');
  return `${prefix}:${normalized}`;
}

function extractPvName(value: string): string {
  if (typeof value === 'string' && value.startsWith('pv:')) {
    return value.substring(3);
  }
  return '';
}
