import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BuilderField } from '../../../utils/jsonata-converter';

@Component({
  selector: 'epistola-builder-field',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="builder-field">
      <div
        class="builder-field__name"
        [class.builder-field__name--clickable]="field.children"
        (click)="field.children && collapseToggle.emit(path)"
      >
        <span *ngIf="field.children" class="builder-field__chevron">{{
          collapsed ? '&#x25B6;' : '&#x25BC;'
        }}</span>
        <span class="builder-field__label">{{ field.name }}</span>
        <span *ngIf="required" class="builder-field__required">*</span>
        <span *ngIf="field.children" class="builder-field__type">(object)</span>
      </div>

      <div class="builder-field__value" *ngIf="!field.children">
        <input
          *ngIf="field.mode === 'ref'"
          type="text"
          class="builder-field__input"
          [ngModel]="field.value"
          (ngModelChange)="valueChange.emit({ path: path, value: $event })"
          [disabled]="disabled"
          placeholder="$doc.path.to.field"
          [attr.list]="'suggestions-' + path.join('-')"
        />
        <datalist *ngIf="field.mode === 'ref'" [id]="'suggestions-' + path.join('-')">
          <option *ngFor="let s of suggestions" [value]="s"></option>
        </datalist>
        <input
          *ngIf="field.mode === 'raw'"
          type="text"
          class="builder-field__input builder-field__input--raw"
          [ngModel]="field.value"
          (ngModelChange)="valueChange.emit({ path: path, value: $event })"
          [disabled]="disabled"
          placeholder="JSONata expression"
        />
        <button
          class="builder-field__mode-toggle"
          (click)="modeToggle.emit(path)"
          [disabled]="disabled"
          [title]="field.mode === 'ref' ? 'Switch to raw JSONata' : 'Switch to reference'"
        >
          {{ field.mode === 'ref' ? 'fx' : '·' }}
        </button>
      </div>

      <div *ngIf="field.children && !collapsed" class="builder-field__children">
        <epistola-builder-field
          *ngFor="let child of field.children; let j = index"
          [field]="child"
          [path]="path.concat(j)"
          [suggestions]="suggestions"
          [disabled]="disabled"
          [collapsed]="isChildCollapsed(j)"
          [collapsedPaths]="collapsedPaths"
          [required]="false"
          (valueChange)="valueChange.emit($event)"
          (modeToggle)="modeToggle.emit($event)"
          (collapseToggle)="collapseToggle.emit($event)"
        ></epistola-builder-field>
      </div>
    </div>
  `,
  styles: [
    `
      .builder-field {
        margin-bottom: 4px;
      }
      .builder-field__name {
        margin-bottom: 2px;
      }
      .builder-field__name--clickable {
        cursor: pointer;
        user-select: none;
      }
      .builder-field__name--clickable:hover {
        color: #0f62fe;
      }
      .builder-field__chevron {
        font-size: 0.7em;
        margin-right: 4px;
      }
      .builder-field__label {
        font-weight: 500;
        font-size: 0.9em;
      }
      .builder-field__required {
        color: #da1e28;
        margin-left: 2px;
      }
      .builder-field__type {
        color: #8d8d8d;
        font-size: 0.8em;
        margin-left: 4px;
      }
      .builder-field__value {
        display: flex;
        align-items: center;
        gap: 4px;
      }
      .builder-field__input {
        flex: 1;
        padding: 6px 8px;
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        font-size: 0.85em;
        font-family: 'IBM Plex Mono', monospace;
      }
      .builder-field__input:focus {
        outline: 2px solid #0f62fe;
        border-color: #0f62fe;
      }
      .builder-field__input--raw {
        background: #f4f4f4;
      }
      .builder-field__mode-toggle {
        width: 28px;
        height: 28px;
        border: 1px solid #e0e0e0;
        border-radius: 4px;
        background: #fff;
        cursor: pointer;
        font-family: monospace;
        font-size: 0.8em;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .builder-field__mode-toggle:hover {
        background: #f4f4f4;
      }
      .builder-field__children {
        border-left: 2px solid #e0e0e0;
        padding-left: 12px;
        margin-top: 4px;
      }
    `,
  ],
})
export class BuilderFieldComponent {
  @Input() field!: BuilderField;
  @Input() path: number[] = [];
  @Input() suggestions: string[] = [];
  @Input() disabled = false;
  @Input() collapsed = false;
  @Input() required = false;
  @Input() collapsedPaths: Set<string> = new Set();
  @Output() valueChange = new EventEmitter<{ path: number[]; value: string }>();
  @Output() modeToggle = new EventEmitter<number[]>();
  @Output() collapseToggle = new EventEmitter<number[]>();

  isChildCollapsed(childIndex: number): boolean {
    return this.collapsedPaths.has(this.path.concat(childIndex).join('.'));
  }
}
