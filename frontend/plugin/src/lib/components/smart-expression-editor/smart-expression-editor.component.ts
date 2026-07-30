/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

import {
  AfterViewInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  NgZone,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { Subject, debounceTime, takeUntil } from 'rxjs';
import * as _jsonata from 'jsonata';
import {
  ReferenceExpressionSegment,
  SimpleExpressionSegment,
  parseSimpleJsonataExpression,
  referenceExpressionSegment,
  serializeSimpleJsonataSegments,
  textExpressionSegment,
  typedExpressionSegment,
} from './simple-jsonata-expression';

const jsonata = (_jsonata as any).default || _jsonata;
const CARET_MARKER = '\u200b';

interface ReferenceOption extends ReferenceExpressionSegment {
  label: string;
  expression: string;
}

interface ReferenceGroup {
  variable: string;
  options: ReferenceOption[];
}

@Component({
  selector: 'epistola-smart-expression-editor',
  standalone: true,
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule],
  templateUrl: './smart-expression-editor.component.html',
  styleUrls: ['./smart-expression-editor.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SmartExpressionEditorComponent implements OnChanges, AfterViewInit, OnDestroy {
  @Input() expression = '';
  @Input() contextVariables: Record<string, string[]> = { doc: [], pv: [], case: [] };
  @Input() disabled = false;
  @Input() required = false;
  @Input() allowTypedValues = true;
  @Input() allowNull = true;
  @Input() placeholder = '';
  @Input() testId = 'epistola-smart-expression';
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  @ViewChild('surface') private surface?: ElementRef<HTMLDivElement>;
  @ViewChild('pickerSearch') private pickerSearch?: ElementRef<HTMLInputElement>;
  @ViewChild('rawTextarea') private rawTextarea?: ElementRef<HTMLTextAreaElement>;

  mode: 'simple' | 'advanced' = 'simple';
  segments: SimpleExpressionSegment[] = [];
  pickerOpen = false;
  pickerQuery = '';
  activeOptionIndex = 0;
  numberEntryOpen = false;
  numberDraft: number | null = null;
  rawExpression = '';
  rawError: string | null = null;
  rawRepresentable = false;
  popoverLeft = 0;
  popoverTop = 0;

  private readonly destroy$ = new Subject<void>();
  private readonly validateRaw$ = new Subject<string>();
  private viewInitialized = false;
  private savedRange: Range | null = null;
  atTrigger: { node: Text; offset: number } | null = null;
  private dismissedAtTrigger: { node: Text; offset: number } | null = null;
  private replacementChip: HTMLElement | null = null;
  private lastEmittedExpression: string | null = null;
  private composing = false;
  private lastValidity: boolean | null = null;
  private simpleOriginalSource = '';
  private simpleDirty = false;

  constructor(
    private readonly cdr: ChangeDetectorRef,
    private readonly zone: NgZone,
  ) {
    this.validateRaw$
      .pipe(debounceTime(300), takeUntil(this.destroy$))
      .subscribe((value) => this.validateRawExpression(value));
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['expression']) {
      const next = this.expression || '';
      if (next === this.lastEmittedExpression) {
        this.lastEmittedExpression = null;
      } else {
        this.loadExpression(next);
      }
    }
    if (changes['contextVariables'] && this.pickerOpen) {
      this.activeOptionIndex = 0;
    }
    if (changes['disabled'] && this.disabled) {
      this.closePicker(false);
    }
  }

  ngAfterViewInit(): void {
    this.viewInitialized = true;
    if (this.mode === 'simple') {
      this.renderSurface();
    } else {
      this.resizeRawTextarea();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get referenceGroups(): ReferenceGroup[] {
    const query = this.pickerQuery.trim().toLocaleLowerCase();
    return Object.entries(this.contextVariables || {})
      .map(([variable, paths]) => ({
        variable,
        options: (paths || [])
          .map((path) => ({
            ...referenceExpressionSegment(variable, path),
            label: path || `$${variable}`,
            expression: path ? `$${variable}.${path}` : `$${variable}`,
          }))
          .filter(
            (option) =>
              !query ||
              option.label.toLocaleLowerCase().includes(query) ||
              option.expression.toLocaleLowerCase().includes(query),
          ),
      }))
      .filter((group) => group.options.length > 0);
  }

  get flatReferenceOptions(): ReferenceOption[] {
    return this.referenceGroups.flatMap((group) => group.options);
  }

  get advancedValid(): boolean {
    return !this.rawError && (!this.required || !!this.rawExpression.trim());
  }

  get optionsId(): string {
    return `${this.testId}-options`;
  }

  groupTranslationKey(variable: string): string {
    switch (variable) {
      case 'doc':
        return 'documentFields';
      case 'pv':
        return 'processVariables';
      case 'case':
        return 'caseProperties';
      default:
        return variable;
    }
  }

  switchToAdvanced(): void {
    if (this.disabled) return;
    this.syncSegmentsFromSurface();
    this.rawExpression = this.simpleDirty
      ? serializeSimpleJsonataSegments(this.segments)
      : this.simpleOriginalSource;
    this.mode = 'advanced';
    this.closePicker(false);
    this.validateRaw$.next(this.rawExpression);
    this.cdr.markForCheck();
    queueMicrotask(() => {
      this.resizeRawTextarea();
      this.rawTextarea?.nativeElement.focus();
    });
  }

  switchToSimple(): void {
    if (this.disabled) return;
    const parsed = parseSimpleJsonataExpression(
      this.rawExpression,
      Object.keys(this.contextVariables || {}),
    );
    if (!parsed.representable || !parsed.expression) return;

    this.segments = parsed.expression.segments;
    this.simpleOriginalSource = this.rawExpression;
    this.simpleDirty = false;
    this.mode = 'simple';
    this.rawError = null;
    this.emitValidity(this.isSimpleValid());
    this.cdr.markForCheck();
    queueMicrotask(() => {
      this.renderSurface();
      this.focusAtEnd();
    });
  }

  onSurfaceInput(): void {
    if (this.composing) return;
    this.syncSegmentsFromSurface();
    this.captureSelection();
    this.openPickerForAtTrigger();
    this.emitSimpleExpression();
  }

  onCompositionStart(): void {
    this.composing = true;
  }

  onCompositionEnd(): void {
    this.composing = false;
    this.onSurfaceInput();
  }

  onSurfaceKeydown(event: KeyboardEvent): void {
    if (this.disabled) return;
    if (event.key === 'Enter') {
      event.preventDefault();
      if (this.pickerOpen) {
        this.selectActiveOption();
      }
      return;
    }
    if (this.pickerOpen && event.key === 'Escape') {
      event.preventDefault();
      this.dismissedAtTrigger = this.atTrigger;
      this.closePicker(true);
      return;
    }
    if (this.pickerOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault();
      this.moveActiveOption(event.key === 'ArrowDown' ? 1 : -1);
      return;
    }
    if (event.key === 'Backspace' || event.key === 'Delete') {
      this.removeAdjacentChip(event);
    }
  }

  onSurfaceClick(event: MouseEvent): void {
    const chip = (event.target as HTMLElement).closest<HTMLElement>('[data-expression-chip]');
    if (chip) {
      event.preventDefault();
      this.openPickerForChip(chip);
      return;
    }
    this.captureSelection();
  }

  onSurfaceFocus(): void {
    this.captureSelection();
  }

  onPaste(event: ClipboardEvent): void {
    event.preventDefault();
    const text = (event.clipboardData?.getData('text/plain') || '').replace(/\r?\n/g, ' ');
    const selection = window.getSelection();
    if (!selection?.rangeCount) return;
    const range = selection.getRangeAt(0);
    range.deleteContents();
    const node = document.createTextNode(text);
    range.insertNode(node);
    range.setStartAfter(node);
    range.collapse(true);
    selection.removeAllRanges();
    selection.addRange(range);
    this.onSurfaceInput();
  }

  openInsertPicker(): void {
    if (this.disabled) return;
    this.restoreSelection();
    this.atTrigger = null;
    this.replacementChip = null;
    this.pickerQuery = '';
    this.numberEntryOpen = false;
    this.pickerOpen = true;
    this.activeOptionIndex = 0;
    this.positionPicker();
    this.cdr.markForCheck();
    queueMicrotask(() => this.pickerSearch?.nativeElement.focus());
  }

  onPickerSearchKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.closePicker(true);
    } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      this.moveActiveOption(event.key === 'ArrowDown' ? 1 : -1);
    } else if (event.key === 'Enter' && !this.numberEntryOpen) {
      event.preventDefault();
      this.selectActiveOption();
    }
  }

  onPickerQueryChange(value: string): void {
    this.pickerQuery = value;
    this.activeOptionIndex = 0;
  }

  selectReference(option: ReferenceOption): void {
    this.insertSegment(option);
  }

  insertBoolean(value: boolean): void {
    this.insertSegment(typedExpressionSegment('boolean', value));
  }

  insertNull(): void {
    if (!this.allowNull) return;
    this.insertSegment(typedExpressionSegment('null', null));
  }

  openNumberEntry(): void {
    this.numberEntryOpen = true;
    this.numberDraft = null;
    this.cdr.markForCheck();
  }

  insertNumber(): void {
    if (
      this.numberDraft === null ||
      !Number.isFinite(this.numberDraft) ||
      typeof this.numberDraft !== 'number'
    ) {
      return;
    }
    this.insertSegment(typedExpressionSegment('number', this.numberDraft));
  }

  onRawInput(value: string, textarea: HTMLTextAreaElement): void {
    this.rawExpression = value;
    this.rawError = null;
    this.rawRepresentable = false;
    this.emitExpression(value);
    this.emitValidity(false);
    this.validateRaw$.next(value);
    this.resizeTextarea(textarea);
  }

  onRawBlur(): void {
    this.validateRawExpression(this.rawExpression);
  }

  closePicker(restoreFocus = false): void {
    this.pickerOpen = false;
    this.numberEntryOpen = false;
    this.replacementChip = null;
    this.atTrigger = null;
    this.cdr.markForCheck();
    if (restoreFocus) {
      queueMicrotask(() => {
        this.restoreSelection();
        this.surface?.nativeElement.focus();
      });
    }
  }

  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(event: MouseEvent): void {
    if (
      this.pickerOpen &&
      !this.surface?.nativeElement.closest('.smart-expression')?.contains(event.target as Node)
    ) {
      this.dismissedAtTrigger = this.atTrigger;
      this.closePicker(false);
    }
  }

  private loadExpression(source: string): void {
    const parsed = parseSimpleJsonataExpression(source, Object.keys(this.contextVariables || {}));
    this.rawExpression = source;
    this.rawError = parsed.error ?? null;
    this.rawRepresentable = parsed.representable;
    if (parsed.representable && parsed.expression) {
      this.mode = 'simple';
      this.segments = parsed.expression.segments;
      this.simpleOriginalSource = source;
      this.simpleDirty = false;
      this.emitValidity(this.isSimpleValid());
      if (this.viewInitialized) {
        queueMicrotask(() => this.renderSurface());
      }
    } else {
      this.mode = 'advanced';
      this.segments = [];
      this.emitValidity(false);
      this.validateRaw$.next(source);
      if (this.viewInitialized) {
        queueMicrotask(() => this.resizeRawTextarea());
      }
    }
    this.cdr.markForCheck();
  }

  private renderSurface(): void {
    const element = this.surface?.nativeElement;
    if (!element) return;
    element.replaceChildren();

    if (this.segments.length === 0) {
      element.append(document.createTextNode(''));
      return;
    }

    for (const segment of this.segments) {
      if (segment.kind === 'text') {
        element.append(document.createTextNode(segment.value));
      } else {
        element.append(document.createTextNode(CARET_MARKER));
        element.append(this.createChipElement(segment));
        element.append(document.createTextNode(CARET_MARKER));
      }
    }
  }

  private createChipElement(
    segment: Exclude<SimpleExpressionSegment, { kind: 'text' }>,
  ): HTMLElement {
    const chip = document.createElement('span');
    chip.className = `smart-expression__chip smart-expression__chip--${segment.kind}`;
    chip.contentEditable = 'false';
    chip.tabIndex = 0;
    chip.dataset['expressionChip'] = 'true';
    chip.dataset['segment'] = JSON.stringify(segment);
    chip.setAttribute('role', 'button');

    const label =
      segment.kind === 'reference'
        ? segment.path
          ? `$${segment.variable}.${segment.path}`
          : `$${segment.variable}`
        : String(segment.value);
    chip.textContent = label;
    chip.setAttribute('aria-label', `${label}. Press Enter to change or Delete to remove.`);
    return chip;
  }

  private syncSegmentsFromSurface(): void {
    const element = this.surface?.nativeElement;
    if (!element) return;

    const segments: SimpleExpressionSegment[] = [];
    let text = '';
    const flushText = () => {
      const clean = text.replaceAll(CARET_MARKER, '');
      if (clean) {
        const previous = segments.at(-1);
        if (previous?.kind === 'text') {
          previous.value += clean;
        } else {
          segments.push(textExpressionSegment(clean));
        }
      }
      text = '';
    };

    const visit = (node: Node) => {
      if (node.nodeType === Node.TEXT_NODE) {
        text += node.textContent || '';
        return;
      }
      const htmlNode = node as HTMLElement;
      if (htmlNode.dataset?.['expressionChip']) {
        flushText();
        try {
          segments.push(JSON.parse(htmlNode.dataset['segment']!));
        } catch {
          // A malformed DOM chip is ignored; it cannot produce invalid JSONata.
        }
        return;
      }
      node.childNodes.forEach(visit);
    };

    element.childNodes.forEach(visit);
    flushText();
    this.segments = segments;
  }

  private emitSimpleExpression(): void {
    this.simpleDirty = true;
    const expression = serializeSimpleJsonataSegments(this.segments);
    this.emitExpression(expression);
    this.emitValidity(this.isSimpleValid());
  }

  private emitExpression(value: string): void {
    this.expression = value;
    this.lastEmittedExpression = value;
    this.expressionChange.emit(value);
  }

  private emitValidity(valid: boolean): void {
    if (valid === this.lastValidity) return;
    this.lastValidity = valid;
    this.validChange.emit(valid);
  }

  private isSimpleValid(): boolean {
    if (!this.required) return true;
    if (this.segments.length === 0) return false;
    if (
      this.segments.length === 1 &&
      ((this.segments[0].kind === 'text' && !this.segments[0].value) ||
        (this.segments[0].kind === 'typed' && this.segments[0].valueType === 'null'))
    ) {
      return false;
    }
    return true;
  }

  private openPickerForAtTrigger(): void {
    const selection = window.getSelection();
    if (!selection?.rangeCount || !selection.isCollapsed) return;
    const range = selection.getRangeAt(0);
    if (range.startContainer.nodeType !== Node.TEXT_NODE) {
      return;
    }

    const node = range.startContainer as Text;
    const beforeCaret = (node.textContent || '').slice(0, range.startOffset);
    const match = beforeCaret.match(/@([^\s@]*)$/);
    if (!match) {
      return;
    }
    const offset = range.startOffset - match[0].length;
    if (this.dismissedAtTrigger?.node === node && this.dismissedAtTrigger.offset === offset) {
      return;
    }

    this.atTrigger = { node, offset };
    this.replacementChip = null;
    this.pickerQuery = match[1];
    this.pickerOpen = true;
    this.numberEntryOpen = false;
    this.activeOptionIndex = 0;
    this.positionPicker(range);
    this.cdr.markForCheck();
  }

  private openPickerForChip(chip: HTMLElement): void {
    this.replacementChip = chip;
    this.atTrigger = null;
    this.savedRange = null;
    this.pickerQuery = '';
    this.pickerOpen = true;
    this.numberEntryOpen = false;
    this.activeOptionIndex = 0;
    this.positionPicker();
    this.cdr.markForCheck();
    queueMicrotask(() => this.pickerSearch?.nativeElement.focus());
  }

  private insertSegment(segment: Exclude<SimpleExpressionSegment, { kind: 'text' }>): void {
    const element = this.surface?.nativeElement;
    if (!element) return;
    const chip = this.createChipElement(segment);

    if (this.replacementChip?.isConnected) {
      this.replacementChip.replaceWith(chip);
    } else {
      this.restoreSelection();
      const selection = window.getSelection();
      const range =
        selection?.rangeCount && element.contains(selection.anchorNode)
          ? selection.getRangeAt(0)
          : this.rangeAtEnd(element);

      if (
        this.atTrigger?.node.isConnected &&
        element.contains(this.atTrigger.node) &&
        range.startContainer === this.atTrigger.node
      ) {
        range.setStart(this.atTrigger.node, this.atTrigger.offset);
      }
      range.deleteContents();
      const after = document.createTextNode(CARET_MARKER);
      range.insertNode(after);
      range.insertNode(chip);
      range.setStart(after, after.length);
      range.collapse(true);
      selection?.removeAllRanges();
      selection?.addRange(range);
    }

    this.dismissedAtTrigger = null;
    this.closePicker(false);
    this.syncSegmentsFromSurface();
    this.emitSimpleExpression();
    element.focus();
    this.captureSelection();
  }

  private selectActiveOption(): void {
    const option = this.flatReferenceOptions[this.activeOptionIndex];
    if (option) {
      this.selectReference(option);
    }
  }

  private moveActiveOption(delta: number): void {
    const count = this.flatReferenceOptions.length;
    if (count === 0) return;
    this.activeOptionIndex = (this.activeOptionIndex + delta + count) % count;
    this.cdr.markForCheck();
  }

  private removeAdjacentChip(event: KeyboardEvent): void {
    const target = event.target as HTMLElement;
    if (target.dataset?.['expressionChip']) {
      event.preventDefault();
      target.remove();
      this.syncSegmentsFromSurface();
      this.emitSimpleExpression();
      this.focusAtEnd();
      return;
    }

    const selection = window.getSelection();
    if (!selection?.isCollapsed || !selection.rangeCount) return;
    const range = selection.getRangeAt(0);
    const adjacent = this.adjacentChip(range, event.key === 'Backspace' ? -1 : 1);
    if (adjacent) {
      event.preventDefault();
      adjacent.remove();
      this.syncSegmentsFromSurface();
      this.emitSimpleExpression();
    }
  }

  private adjacentChip(range: Range, direction: -1 | 1): HTMLElement | null {
    let node: Node | null = range.startContainer;
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent || '';
      const atBoundary =
        direction === -1 ? range.startOffset === 0 : range.startOffset === text.length;
      if (!atBoundary) return null;
      node = direction === -1 ? node.previousSibling : node.nextSibling;
    } else {
      const childIndex = range.startOffset + (direction === -1 ? -1 : 0);
      node = node.childNodes[childIndex] || null;
    }

    while (node?.nodeType === Node.TEXT_NODE && node.textContent === CARET_MARKER) {
      node = direction === -1 ? node.previousSibling : node.nextSibling;
    }
    const htmlNode = node as HTMLElement | null;
    return htmlNode?.dataset?.['expressionChip'] ? htmlNode : null;
  }

  private validateRawExpression(value: string): void {
    if (!value.trim()) {
      this.rawError = this.required ? 'A value is required.' : null;
      this.rawRepresentable = true;
      this.emitValidity(!this.required);
      this.cdr.markForCheck();
      return;
    }

    try {
      jsonata(value);
      this.rawError = null;
      this.rawRepresentable = parseSimpleJsonataExpression(
        value,
        Object.keys(this.contextVariables || {}),
      ).representable;
      this.emitValidity(true);
    } catch (error: any) {
      this.rawError = error?.message || 'Invalid JSONata expression';
      this.rawRepresentable = false;
      this.emitValidity(false);
    }
    this.cdr.markForCheck();
  }

  private captureSelection(): void {
    const element = this.surface?.nativeElement;
    const selection = window.getSelection();
    if (!element || !selection?.rangeCount || !element.contains(selection.anchorNode)) {
      return;
    }
    this.savedRange = selection.getRangeAt(0).cloneRange();
  }

  private restoreSelection(): void {
    const element = this.surface?.nativeElement;
    if (!element) return;
    const range =
      this.savedRange &&
      this.savedRange.startContainer.isConnected &&
      element.contains(this.savedRange.startContainer)
        ? this.savedRange
        : this.rangeAtEnd(element);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  }

  private focusAtEnd(): void {
    const element = this.surface?.nativeElement;
    if (!element) return;
    element.focus();
    const range = this.rangeAtEnd(element);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    this.savedRange = range.cloneRange();
  }

  private rangeAtEnd(element: HTMLElement): Range {
    const range = document.createRange();
    range.selectNodeContents(element);
    range.collapse(false);
    return range;
  }

  private positionPicker(range?: Range): void {
    const wrapper = this.surface?.nativeElement.parentElement;
    const anchor = range?.getBoundingClientRect();
    const wrapperRect = wrapper?.getBoundingClientRect();
    this.popoverLeft =
      anchor && wrapperRect && anchor.left > 0 ? Math.max(0, anchor.left - wrapperRect.left) : 0;
    this.popoverTop =
      anchor && wrapperRect && anchor.bottom > 0
        ? Math.max(36, anchor.bottom - wrapperRect.top + 4)
        : 40;
  }

  private resizeRawTextarea(): void {
    if (this.rawTextarea) {
      this.resizeTextarea(this.rawTextarea.nativeElement);
    }
  }

  private resizeTextarea(textarea: HTMLTextAreaElement): void {
    this.zone.runOutsideAngular(() => {
      textarea.style.height = 'auto';
      textarea.style.height = `${Math.min(Math.max(textarea.scrollHeight, 72), 240)}px`;
    });
  }
}
