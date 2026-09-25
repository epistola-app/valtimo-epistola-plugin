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
import { Component, EventEmitter, Input, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  FunctionConfigurationComponent,
  FunctionConfigurationData,
  PluginTranslatePipeModule,
} from '@valtimo/plugin';
import { FormModule, FormOutput, InputModule } from '@valtimo/components';
import { BehaviorSubject, combineLatest, Observable, of, Subscription, take } from 'rxjs';
import { delay, startWith } from 'rxjs/operators';

/** What this action needs: where the composed letter is, and where its result goes. */
export interface GenerateComposedDocumentConfig {
  letterVariable: string;
  filename?: string;
  correlationId?: string;
  resultProcessVariable: string;
}

export const DEFAULT_LETTER_VARIABLE = 'epistolaLetter';

export function isGenerateComposedDocumentConfigValid(
  config: GenerateComposedDocumentConfig | null,
): boolean {
  return !!config?.resultProcessVariable?.trim();
}

/**
 * Configures the action that generates whatever a letter composer put on a process variable.
 *
 * <p>Deliberately small: there is no template to pick and no mapping to write, because the composer
 * resolved both while the employee was looking at the preview. That is also why this is a separate
 * action from generate-document rather than a mode of it.
 */
@Component({
  selector: 'epistola-generate-composed-document-configuration',
  templateUrl: './generate-composed-document-configuration.component.html',
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule],
})
export class GenerateComposedDocumentConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<GenerateComposedDocumentConfig>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<FunctionConfigurationData> =
    new EventEmitter<FunctionConfigurationData>();

  /** Resolved before the form renders, to avoid the v-input `[defaultValue]` binding race. */
  resolvedPrefill: Partial<GenerateComposedDocumentConfig> = {};
  readonly prefillResolved$ = new BehaviorSubject<boolean>(false);
  readonly defaultLetterVariable = DEFAULT_LETTER_VARIABLE;

  safeDisabled$!: Observable<boolean>;

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<GenerateComposedDocumentConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  ngOnInit(): void {
    this.safeDisabled$ = this.disabled$.pipe(startWith(true), delay(0));
    const prefill$ = this.prefillConfiguration$ ?? of({} as GenerateComposedDocumentConfig);
    prefill$.pipe(take(1)).subscribe((prefill) => {
      this.resolvedPrefill = prefill ?? {};
      this.prefillResolved$.next(true);
    });
    this.openSaveSubscription();
  }

  ngOnDestroy(): void {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as GenerateComposedDocumentConfig;
    this.formValue$.next(formValue);
    const valid = isGenerateComposedDocumentConfigValid(formValue);
    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid && formValue) {
            this.configuration.emit({
              ...formValue,
              letterVariable: formValue.letterVariable?.trim() || DEFAULT_LETTER_VARIABLE,
            });
          }
        });
    });
  }
}
