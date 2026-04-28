import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { TemplateField } from '../../models';

@Component({
  selector: 'epistola-expected-structure',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule],
  template: `
    <div class="expected">
      <div class="expected__header">
        {{ 'expectedStructure' | pluginTranslate: 'epistola' | async }}
      </div>
      <div *ngIf="!templateFields || templateFields.length === 0" class="expected__empty">
        {{ 'expectedStructureLoading' | pluginTranslate: 'epistola' | async }}
      </div>
      <pre *ngIf="templateFields && templateFields.length > 0" class="expected__code">{{
        structureText
      }}</pre>
    </div>
  `,
  styles: [
    `
      .expected {
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        overflow: hidden;
        height: 100%;
        display: flex;
        flex-direction: column;
      }
      .expected__header {
        padding: 6px 12px;
        background: #f4f4f4;
        border-bottom: 1px solid #e0e0e0;
        font-size: 0.75em;
        color: #6f6f6f;
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }
      .expected__code {
        flex: 1;
        font-family: 'IBM Plex Mono', monospace;
        font-size: 0.8em;
        line-height: 1.5;
        margin: 0;
        padding: 8px 12px;
        white-space: pre-wrap;
        overflow-y: auto;
      }
      .expected__empty {
        padding: 8px 12px;
        color: #8d8d8d;
        font-size: 0.85em;
        font-style: italic;
      }
    `,
  ],
})
export class ExpectedStructureComponent implements OnChanges {
  @Input() templateFields: TemplateField[] = [];

  structureText: string = '{}';

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['templateFields']) {
      this.structureText = this.buildStructure(this.templateFields, 0);
    }
  }

  private buildStructure(fields: TemplateField[], depth: number): string {
    if (!fields || fields.length === 0) return '{}';
    const indent = '  '.repeat(depth + 1);
    const closing = '  '.repeat(depth);

    const lines = fields.map((f) => {
      const req = f.required ? ' (required)' : '';
      if (f.fieldType === 'OBJECT' && f.children?.length) {
        const nested = this.buildStructure(f.children, depth + 1);
        return `${indent}"${f.name}": ${nested}${req}`;
      }
      if (f.fieldType === 'ARRAY') {
        if (f.children?.length) {
          const itemStructure = this.buildStructure(f.children, depth + 2);
          return `${indent}"${f.name}": [${itemStructure}]${req}`;
        }
        return `${indent}"${f.name}": array${req}`;
      }
      return `${indent}"${f.name}": ${f.type || 'any'}${req}`;
    });

    return `{\n${lines.join(',\n')}\n${closing}}`;
  }
}
