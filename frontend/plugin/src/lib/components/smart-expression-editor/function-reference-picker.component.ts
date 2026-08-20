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
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import {
  FunctionReferenceGroup,
  FunctionReferenceOption,
  FunctionSchemaDiagnostic,
} from './function-reference-catalog';

@Component({
  selector: 'epistola-function-reference-picker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './function-reference-picker.component.html',
  styleUrls: ['./function-reference-picker.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FunctionReferencePickerComponent {
  @Input() public groups: FunctionReferenceGroup[] = [];
  @Input() public diagnostics: FunctionSchemaDiagnostic[] = [];
  @Input() public activeOption: FunctionReferenceOption | null = null;

  @Output() public toggleField = new EventEmitter<{
    event: MouseEvent;
    option: FunctionReferenceOption;
  }>();
  @Output() public optionMouseDown = new EventEmitter<{
    event: MouseEvent;
    option: FunctionReferenceOption;
  }>();
  @Output() public optionClick = new EventEmitter<FunctionReferenceOption>();

  // Angular templates require trackBy callbacks on the component instance.
  // eslint-disable-next-line class-methods-use-this
  public readonly trackGroup = (_index: number, group: FunctionReferenceGroup): string => group.id;

  // Angular templates require trackBy callbacks on the component instance.
  // eslint-disable-next-line class-methods-use-this
  public readonly trackOption = (_index: number, option: FunctionReferenceOption): string =>
    option.schemaField.id;
}
