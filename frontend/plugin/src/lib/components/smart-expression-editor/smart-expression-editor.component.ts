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
import { InputLabelModule, SelectedValue, SelectItem, SelectModule } from '@valtimo/components';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { Subject, debounceTime, takeUntil } from 'rxjs';
import * as _jsonata from 'jsonata';
import {
  decodeJsonataStringLiteral,
  encodeJsonataStringLiteral,
} from '../../utils/jsonata-literal';
import { renderJsonataPath } from '../../utils/jsonata-path';
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
const MODE_ORDER = ['select', 'simple', 'advanced'] as const;

type ExpressionEditorMode = (typeof MODE_ORDER)[number];

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
  imports: [CommonModule, FormsModule, InputLabelModule, SelectModule, PluginTranslatePipeModule],
  templateUrl: './smart-expression-editor.component.html',
  styleUrls: ['./smart-expression-editor.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SmartExpressionEditorComponent implements OnChanges, AfterViewInit, OnDestroy {
  @Input() expression = '';
  @Input() contextVariables: Record<string, string[]> = { doc: [], pv: [], case: [] };
  @Input() disabled = false;
  @Input() required = false;
  @Input() compact = false;
  @Input() allowTypedValues = true;
  @Input() allowNull = true;
  @Input() placeholder = '';
  @Input() testId = 'epistola-smart-expression';
  @Input() title = '';
  @Input() tooltip = '';
  /** Null disables the Select view; an empty array keeps it available for an empty expression. */
  @Input() selectOptions: SelectItem[] | null = null;
  @Input() selectLoading = false;
  @Output() expressionChange = new EventEmitter<string>();
  @Output() validChange = new EventEmitter<boolean>();

  private surface?: ElementRef<HTMLDivElement>;
  @ViewChild('pickerSearch') private pickerSearch?: ElementRef<HTMLInputElement>;
  @ViewChild('rawTextarea') private rawTextarea?: ElementRef<HTMLTextAreaElement>;
  private focusSimpleOnAttach = false;

  @ViewChild('surface')
  private set surfaceView(value: ElementRef<HTMLDivElement> | undefined) {
    this.surface = value;
    if (value && this.mode === 'simple') {
      this.renderSurface();
      if (this.focusSimpleOnAttach) {
        this.focusSimpleOnAttach = false;
        this.focusAtEnd();
      }
    }
  }

  mode: ExpressionEditorMode = 'simple';
  selectedValue = '';
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
  private savedCaretOffset: number | null = null;
  private insertionRange: { start: number; end: number } | null = null;
  atTrigger: { node: Text; offset: number } | null = null;
  private dismissedAtTrigger: { node: Text; offset: number } | null = null;
  private lastEmittedExpression: string | null = null;
  private composing = false;
  private lastValidity: boolean | null = null;
  private simpleOriginalSource = '';
  private simpleDirty = false;
  private modeChosenByUser = false;
  private selectCompatible = false;

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
        this.modeChosenByUser = false;
        this.loadExpression(next);
      }
    }
    if (changes['selectOptions']) {
      this.reconcileSelectView();
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

  get customReferenceOptions(): ReferenceOption[] {
    const query = this.pickerQuery.trim().replace(/^\$/, '');
    if (!query) return [];

    const [root, ...pathSegments] = query.split('.');
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(root)) return [];
    if (pathSegments.some((segment) => !segment)) return [];

    const path = pathSegments.join('.');
    const explicitlyScoped = ['case', 'pv', 'doc'].includes(root) && !!path;
    const candidates = explicitlyScoped
      ? [referenceExpressionSegment(root, path)]
      : [
          referenceExpressionSegment('case', query),
          referenceExpressionSegment('pv', query),
          referenceExpressionSegment('doc', query),
          referenceExpressionSegment(root, path),
        ];
    const knownExpressions = new Set(this.flatReferenceOptions.map((option) => option.expression));
    const seen = new Set<string>();

    return candidates.flatMap((candidate) => {
      const expression = candidate.path
        ? renderJsonataPath(candidate.variable, candidate.path)
        : `$${candidate.variable}`;
      if (knownExpressions.has(expression) || seen.has(expression)) return [];
      seen.add(expression);
      return [
        {
          ...candidate,
          label: query,
          expression,
        },
      ];
    });
  }

  customReferenceLabelKey(option: ReferenceOption): string {
    switch (option.variable) {
      case 'case':
        return 'caseProperties';
      case 'pv':
        return 'processVariables';
      case 'doc':
        return 'documentFields';
      default:
        return 'expressionEditorJsonataVariable';
    }
  }

  get selectableReferenceOptions(): ReferenceOption[] {
    return [...this.flatReferenceOptions, ...this.customReferenceOptions];
  }

  get advancedValid(): boolean {
    return !this.rawError && (!this.required || !!this.rawExpression.trim());
  }

  get availableModes(): ExpressionEditorMode[] {
    return MODE_ORDER.filter((mode) => this.canUseMode(mode));
  }

  get nextMode(): ExpressionEditorMode | null {
    const available = this.availableModes;
    if (available.length <= 1) return null;

    const currentIndex = MODE_ORDER.indexOf(this.mode);
    for (let offset = 1; offset <= MODE_ORDER.length; offset++) {
      const candidate = MODE_ORDER[(currentIndex + offset) % MODE_ORDER.length];
      if (available.includes(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  get nextModeTranslationKey(): string {
    switch (this.nextMode) {
      case null:
        return 'expressionEditorAdvancedOnly';
      case 'select':
        return 'switchToDropdown';
      case 'simple':
        return 'expressionEditorSwitchVisual';
      case 'advanced':
      default:
        return 'expressionEditorSwitchAdvanced';
    }
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
    this.rawRepresentable = true;
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
    const parsed = parseSimpleJsonataExpression(this.rawExpression);
    if (!parsed.representable || !parsed.expression) return;

    this.segments = parsed.expression.segments;
    this.simpleOriginalSource = this.rawExpression;
    this.simpleDirty = false;
    this.mode = 'simple';
    this.rawError = null;
    this.emitValidity(this.isSimpleValid());
    this.focusSimpleOnAttach = true;
    this.cdr.markForCheck();
    queueMicrotask(() => {
      if (this.surface?.nativeElement.isConnected) {
        this.renderSurface();
        this.focusSimpleOnAttach = false;
        this.focusAtEnd();
      }
    });
  }

  switchToSelect(): void {
    if (this.disabled || !this.canUseMode('select')) return;
    this.selectedValue = decodeJsonataStringLiteral(this.currentExpressionSource()) || '';
    this.rawExpression = this.currentExpressionSource();
    this.mode = 'select';
    this.rawError = null;
    this.closePicker(false);
    this.emitValidity(!this.required || !!this.selectedValue);
    this.cdr.markForCheck();
  }

  toggleMode(): void {
    const next = this.nextMode;
    if (!next || this.disabled) return;

    this.modeChosenByUser = true;
    switch (next) {
      case 'select':
        this.switchToSelect();
        break;
      case 'simple':
        this.switchToSimple();
        break;
      case 'advanced':
        this.switchToAdvanced();
        break;
    }
  }

  onSelectedValueChange(value: SelectedValue | undefined): void {
    this.modeChosenByUser = true;
    this.selectedValue = value == null || Array.isArray(value) ? '' : String(value);
    const expression = this.selectedValue ? encodeJsonataStringLiteral(this.selectedValue) : '';
    this.rawExpression = expression;
    this.rawRepresentable = true;
    this.selectCompatible = this.computeSelectCompatibility(expression);
    this.segments = this.selectedValue ? [textExpressionSegment(this.selectedValue)] : [];
    this.simpleOriginalSource = expression;
    this.simpleDirty = false;
    this.emitExpression(expression);
    this.emitValidity(!this.required || !!this.selectedValue);
  }

  onSurfaceInput(): void {
    if (this.composing) return;
    this.modeChosenByUser = true;
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
    this.syncSegmentsFromSurface();
    const offset = this.savedCaretOffset ?? this.expressionLength();
    this.insertionRange = { start: offset, end: offset };
    this.atTrigger = null;
    this.pickerQuery = '';
    this.numberEntryOpen = false;
    this.pickerOpen = true;
    this.activeOptionIndex = 0;
    this.positionPicker();
    this.cdr.markForCheck();
    this.focusPickerSearchAfterRender();
  }

  onInsertMouseDown(event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    if (this.pickerOpen) {
      this.closePicker(true);
    } else {
      this.openInsertPicker();
    }
  }

  onInsertClick(event: MouseEvent): void {
    if (event.detail !== 0) return;
    if (this.pickerOpen) {
      this.closePicker(true);
    } else {
      this.openInsertPicker();
    }
  }

  onPickerSearchKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.useAtTriggerAsLiteral();
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
    this.cdr.markForCheck();
  }

  onLiteralAtMouseDown(event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.useAtTriggerAsLiteral();
  }

  onLiteralAtClick(): void {
    if (this.pickerOpen) {
      this.useAtTriggerAsLiteral();
    }
  }

  selectReference(option: ReferenceOption): void {
    this.insertSegment(option);
  }

  onReferenceMouseDown(event: MouseEvent, option: ReferenceOption): void {
    event.preventDefault();
    event.stopPropagation();
    this.selectReference(option);
  }

  onReferenceClick(option: ReferenceOption): void {
    if (this.pickerOpen) {
      this.selectReference(option);
    }
  }

  onTypedValueMouseDown(event: MouseEvent, value: 'number' | 'true' | 'false' | 'null'): void {
    event.preventDefault();
    event.stopPropagation();
    this.applyTypedValue(value);
  }

  onTypedValueClick(value: 'number' | 'true' | 'false' | 'null'): void {
    if (!this.pickerOpen) return;
    this.applyTypedValue(value);
  }

  private applyTypedValue(value: 'number' | 'true' | 'false' | 'null'): void {
    switch (value) {
      case 'number':
        this.openNumberEntry();
        break;
      case 'true':
        this.insertBoolean(true);
        break;
      case 'false':
        this.insertBoolean(false);
        break;
      case 'null':
        this.insertNull();
        break;
    }
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
    this.modeChosenByUser = true;
    this.rawExpression = value;
    this.rawError = null;
    this.rawRepresentable = false;
    this.selectCompatible = false;
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
    this.insertionRange = null;
    this.atTrigger = null;
    this.cdr.markForCheck();
    if (restoreFocus) {
      queueMicrotask(() => {
        this.surface?.nativeElement.focus();
        this.focusAtLogicalOffset(this.savedCaretOffset ?? this.expressionLength());
      });
    }
  }

  private useAtTriggerAsLiteral(): void {
    if (!this.atTrigger) {
      this.closePicker(true);
      return;
    }
    this.dismissedAtTrigger = this.atTrigger;
    const offset = this.insertionRange?.end ?? this.savedCaretOffset ?? this.expressionLength();
    this.closePicker(false);
    queueMicrotask(() => {
      this.surface?.nativeElement.focus();
      this.focusAtLogicalOffset(offset);
    });
  }

  @HostListener('document:mousedown', ['$event'])
  onDocumentMouseDown(event: MouseEvent): void {
    const target = event.target;
    if (
      this.pickerOpen &&
      (!(target instanceof Element) || !target.closest('.smart-expression__picker'))
    ) {
      this.dismissedAtTrigger = this.atTrigger;
      this.closePicker(false);
    }
  }

  private loadExpression(source: string): void {
    const parsed = parseSimpleJsonataExpression(source);
    this.rawExpression = source;
    this.rawError = parsed.error ?? null;
    this.rawRepresentable = parsed.representable;
    this.selectCompatible = this.computeSelectCompatibility(source);
    if (this.selectCompatible) {
      this.mode = 'select';
      this.selectedValue = decodeJsonataStringLiteral(source) || '';
      this.segments = parsed.expression?.segments || [];
      this.simpleOriginalSource = source;
      this.simpleDirty = false;
      this.emitValidity(!this.required || !!this.selectedValue);
    } else if (parsed.representable && parsed.expression) {
      this.mode = 'simple';
      this.selectedValue = '';
      this.segments = parsed.expression.segments;
      this.simpleOriginalSource = source;
      this.simpleDirty = false;
      this.emitValidity(this.isSimpleValid());
      if (this.viewInitialized) {
        queueMicrotask(() => this.renderSurface());
      }
    } else {
      this.mode = 'advanced';
      this.selectedValue = '';
      this.segments = [];
      this.emitValidity(false);
      this.validateRaw$.next(source);
      if (this.viewInitialized) {
        queueMicrotask(() => this.resizeRawTextarea());
      }
    }
    this.cdr.markForCheck();
  }

  private reconcileSelectView(): void {
    this.selectCompatible = this.computeSelectCompatibility(this.currentExpressionSource());
    if (this.mode === 'select' && !this.canUseMode('select')) {
      this.loadExpression(this.currentExpressionSource());
      return;
    }
    if (!this.modeChosenByUser && this.selectCompatible) {
      this.loadExpression(this.currentExpressionSource());
    }
  }

  private canUseMode(mode: ExpressionEditorMode): boolean {
    switch (mode) {
      case 'select':
        return this.selectCompatible;
      case 'simple':
        return this.rawRepresentable;
      case 'advanced':
        return true;
      default:
        return false;
    }
  }

  private computeSelectCompatibility(source: string): boolean {
    if (this.selectOptions === null) return false;
    if (!source.trim()) return true;
    const literal = decodeJsonataStringLiteral(source);
    return (
      literal !== undefined && this.selectOptions.some((option) => String(option.id) === literal)
    );
  }

  private currentExpressionSource(): string {
    if (this.mode === 'simple') {
      return this.simpleDirty
        ? serializeSimpleJsonataSegments(this.segments)
        : this.simpleOriginalSource;
    }
    return this.rawExpression;
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
      const clean = text.split(CARET_MARKER).join('');
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
    this.rawRepresentable = true;
    this.selectCompatible = this.computeSelectCompatibility(expression);
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
    const caretOffset = this.logicalOffsetAtRange(range);
    if (caretOffset === null) return;
    this.insertionRange = {
      start: Math.max(0, caretOffset - match[0].length),
      end: caretOffset,
    };
    this.pickerQuery = match[1];
    this.pickerOpen = true;
    this.numberEntryOpen = false;
    this.activeOptionIndex = 0;
    this.positionPicker(range);
    this.cdr.markForCheck();
    this.focusPickerSearchAfterRender();
  }

  private openPickerForChip(chip: HTMLElement): void {
    this.syncSegmentsFromSurface();
    const offset = this.logicalOffsetBeforeNode(chip);
    if (offset === null) return;
    this.insertionRange = { start: offset, end: offset + 1 };
    this.atTrigger = null;
    this.pickerQuery = '';
    this.pickerOpen = true;
    this.numberEntryOpen = false;
    this.activeOptionIndex = 0;
    this.positionPicker();
    this.cdr.markForCheck();
    this.focusPickerSearchAfterRender();
  }

  private focusPickerSearchAfterRender(): void {
    const focus = () => {
      const input = this.pickerSearch?.nativeElement;
      if (!this.pickerOpen || !input?.isConnected) return;
      input.focus({ preventScroll: true });
    };

    queueMicrotask(focus);
    this.zone.runOutsideAngular(() => {
      if (typeof requestAnimationFrame === 'function') {
        requestAnimationFrame(focus);
      }
    });
  }

  private insertSegment(segment: Exclude<SimpleExpressionSegment, { kind: 'text' }>): void {
    const element = this.surface?.nativeElement;
    if (!element) return;
    this.syncSegmentsFromSurface();
    const insertion = this.insertionRange ?? {
      start: this.savedCaretOffset ?? this.expressionLength(),
      end: this.savedCaretOffset ?? this.expressionLength(),
    };
    const nextOffset = this.replaceExpressionRange(insertion.start, insertion.end, segment);

    this.dismissedAtTrigger = null;
    this.closePicker(false);
    this.renderSurface();
    this.emitSimpleExpression();
    element.focus();
    this.focusAtLogicalOffset(nextOffset);
    this.restoreCaretAfterPickerCloses(element, nextOffset);
  }

  private restoreCaretAfterPickerCloses(element: HTMLDivElement, offset: number): void {
    this.zone.runOutsideAngular(() => {
      const restore = () => {
        if (
          this.mode !== 'simple' ||
          this.surface?.nativeElement !== element ||
          !element.isConnected
        ) {
          return;
        }
        element.focus({ preventScroll: true });
        this.focusAtLogicalOffset(offset);
      };

      if (typeof requestAnimationFrame === 'function') {
        requestAnimationFrame(restore);
      } else {
        queueMicrotask(restore);
      }
    });
  }

  private selectActiveOption(): void {
    const option = this.selectableReferenceOptions[this.activeOptionIndex];
    if (option) {
      this.selectReference(option);
    }
  }

  private moveActiveOption(delta: number): void {
    const count = this.selectableReferenceOptions.length;
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
      this.selectCompatible = this.computeSelectCompatibility(value);
      this.emitValidity(!this.required);
      this.cdr.markForCheck();
      return;
    }

    try {
      jsonata(value);
      this.rawError = null;
      this.rawRepresentable = parseSimpleJsonataExpression(value).representable;
      this.selectCompatible = this.computeSelectCompatibility(value);
      this.emitValidity(true);
    } catch (error: any) {
      this.rawError = error?.message || 'Invalid JSONata expression';
      this.rawRepresentable = false;
      this.selectCompatible = false;
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
    this.savedCaretOffset = this.logicalOffsetAtRange(selection.getRangeAt(0));
  }

  private logicalOffsetAtRange(range: Range): number | null {
    const element = this.surface?.nativeElement;
    if (!element || !element.contains(range.startContainer)) return null;
    const preceding = document.createRange();
    preceding.selectNodeContents(element);
    preceding.setEnd(range.startContainer, range.startOffset);
    return this.logicalLength(preceding.cloneContents());
  }

  private logicalOffsetBeforeNode(node: Node): number | null {
    const element = this.surface?.nativeElement;
    if (!element || !element.contains(node)) return null;
    const preceding = document.createRange();
    preceding.selectNodeContents(element);
    preceding.setEndBefore(node);
    return this.logicalLength(preceding.cloneContents());
  }

  private logicalLength(node: Node): number {
    if (node.nodeType === Node.TEXT_NODE) {
      return (node.textContent || '').split(CARET_MARKER).join('').length;
    }
    const htmlNode = node as HTMLElement;
    if (htmlNode.dataset?.['expressionChip']) {
      return 1;
    }
    return Array.from(node.childNodes).reduce(
      (length, child) => length + this.logicalLength(child),
      0,
    );
  }

  private expressionLength(): number {
    return this.segments.reduce(
      (length, segment) => length + (segment.kind === 'text' ? segment.value.length : 1),
      0,
    );
  }

  private replaceExpressionRange(
    start: number,
    end: number,
    segment: Exclude<SimpleExpressionSegment, { kind: 'text' }>,
  ): number {
    const atoms: SimpleExpressionSegment[] = [];
    for (const item of this.segments) {
      if (item.kind === 'text') {
        atoms.push(...item.value.split('').map((value) => textExpressionSegment(value)));
      } else {
        atoms.push(item);
      }
    }
    const safeStart = Math.max(0, Math.min(start, atoms.length));
    const safeEnd = Math.max(safeStart, Math.min(end, atoms.length));
    atoms.splice(safeStart, safeEnd - safeStart, segment);

    this.segments = [];
    for (const atom of atoms) {
      const previous = this.segments.at(-1);
      if (atom.kind === 'text' && previous?.kind === 'text') {
        previous.value += atom.value;
      } else {
        this.segments.push(atom);
      }
    }
    return safeStart + 1;
  }

  private focusAtLogicalOffset(offset: number): void {
    const element = this.surface?.nativeElement;
    if (!element) return;
    const range = this.rangeAtLogicalOffset(element, offset);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    this.savedCaretOffset = offset;
  }

  private rangeAtLogicalOffset(element: HTMLElement, requestedOffset: number): Range {
    let remaining = Math.max(0, requestedOffset);
    const range = document.createRange();
    const childNodes = Array.from(element.childNodes);
    for (let index = 0; index < childNodes.length; index++) {
      const node = childNodes[index];
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.textContent || '';
        const logicalText = text.split(CARET_MARKER).join('');
        if (remaining <= logicalText.length && logicalText.length > 0) {
          range.setStart(node, Math.min(remaining, text.length));
          range.collapse(true);
          return range;
        }
        remaining -= logicalText.length;
        continue;
      }
      const htmlNode = node as HTMLElement;
      if (htmlNode.dataset?.['expressionChip']) {
        if (remaining === 0) {
          range.setStart(element, index);
          range.collapse(true);
          return range;
        }
        remaining--;
      }
    }
    return this.rangeAtEnd(element);
  }

  private focusAtEnd(): void {
    const element = this.surface?.nativeElement;
    if (!element) return;
    element.focus();
    const range = this.rangeAtEnd(element);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    this.savedCaretOffset = this.expressionLength();
  }

  private rangeAtEnd(element: HTMLElement): Range {
    const range = document.createRange();
    range.selectNodeContents(element);
    range.collapse(false);
    return range;
  }

  private positionPicker(range?: Range): void {
    const surface = this.surface?.nativeElement;
    const wrapper = surface?.parentElement;
    const container = surface?.closest<HTMLElement>('.smart-expression');
    const anchor = range?.getBoundingClientRect();
    const wrapperRect = wrapper?.getBoundingClientRect();
    const containerRect = container?.getBoundingClientRect();
    this.popoverLeft =
      anchor && containerRect && anchor.left > 0
        ? Math.max(0, anchor.left - containerRect.left)
        : 0;
    this.popoverTop =
      wrapperRect && containerRect && wrapperRect.bottom > containerRect.top
        ? wrapperRect.bottom - containerRect.top + 6
        : (wrapper?.offsetTop || 0) + (wrapper?.offsetHeight || 40) + 6;
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
