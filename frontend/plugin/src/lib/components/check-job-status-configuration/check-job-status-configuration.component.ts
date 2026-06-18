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
import { FunctionConfigurationComponent, PluginTranslatePipeModule } from '@valtimo/plugin';
import { FormModule, FormOutput, InputModule } from '@valtimo/components';
import { BehaviorSubject, combineLatest, Observable, of, Subscription, take } from 'rxjs';
import { delay, startWith } from 'rxjs/operators';
import { CheckJobStatusConfig } from '../../models';

@Component({
  selector: 'epistola-check-job-status-configuration',
  templateUrl: './check-job-status-configuration.component.html',
  styleUrls: ['./check-job-status-configuration.component.scss'],
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule],
})
export class CheckJobStatusConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<CheckJobStatusConfig>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<CheckJobStatusConfig> =
    new EventEmitter<CheckJobStatusConfig>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<CheckJobStatusConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  /** Resolved synchronously before the v-form renders — see download-document-configuration for the why. */
  resolvedPrefill: Partial<CheckJobStatusConfig> = {};
  readonly prefillResolved$ = new BehaviorSubject<boolean>(false);

  safeDisabled$!: Observable<boolean>;

  ngOnInit(): void {
    this.safeDisabled$ = this.disabled$.pipe(startWith(true), delay(0));
    const prefill$ = this.prefillConfiguration$ ?? of({} as CheckJobStatusConfig);
    prefill$.pipe(take(1)).subscribe((prefill) => {
      this.resolvedPrefill = prefill ?? {};
      this.prefillResolved$.next(true);
    });
    this.openSaveSubscription();
  }

  ngOnDestroy() {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as CheckJobStatusConfig;
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: CheckJobStatusConfig): void {
    const valid = !!(formValue?.requestIdVariable && formValue?.statusVariable);
    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid && formValue) {
            this.configuration.emit(formValue);
          }
        });
    });
  }
}
