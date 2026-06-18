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
import { FormModule, FormOutput, InputModule, SelectItem, SelectModule } from '@valtimo/components';
import { BehaviorSubject, combineLatest, Observable, of, Subscription, take } from 'rxjs';
import { delay, startWith } from 'rxjs/operators';
import { DownloadDocumentConfig } from '../../models';
import {
  DEFAULT_STORAGE_TARGET,
  isDownloadDocumentConfigValid,
  resolveStorageTarget,
} from './download-document-config.util';

@Component({
  selector: 'epistola-download-document-configuration',
  templateUrl: './download-document-configuration.component.html',
  styleUrls: ['./download-document-configuration.component.scss'],
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule, SelectModule],
})
export class DownloadDocumentConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<DownloadDocumentConfig>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  // Framework's FunctionConfigurationData (index type) to satisfy the invariant
  // EventEmitter contract under strict mode; emitted values remain the typed config.
  @Output() configuration: EventEmitter<FunctionConfigurationData> =
    new EventEmitter<FunctionConfigurationData>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<DownloadDocumentConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  /**
   * Resolved prefill — populated synchronously before the v-form renders. Avoids the
   * v-input `[defaultValue]` async-binding race that otherwise drops one of the
   * fields when prefill arrives after mount.
   */
  resolvedPrefill: Partial<DownloadDocumentConfig> = {};
  readonly prefillResolved$ = new BehaviorSubject<boolean>(false);

  safeDisabled$!: Observable<boolean>;

  /**
   * Static option set for the storage target. Values match the backend
   * {@code DocumentStorageTarget} enum constants; labels are explained further via the
   * translated field title/tooltip.
   */
  readonly storageTargetOptions: SelectItem[] = [
    { id: 'TEMPORARY_RESOURCE', text: 'Temporary resource storage' },
    { id: 'PROCESS_VARIABLE', text: 'Process variable (inline bytes)' },
  ];
  readonly defaultStorageTarget = DEFAULT_STORAGE_TARGET;

  /** Drives which output-variable field is shown (resource id vs inline content). */
  readonly selectedTarget$ = new BehaviorSubject<string>(this.defaultStorageTarget);

  ngOnInit(): void {
    this.safeDisabled$ = this.disabled$.pipe(startWith(true), delay(0));
    const prefill$ = this.prefillConfiguration$ ?? of({} as DownloadDocumentConfig);
    prefill$.pipe(take(1)).subscribe((prefill) => {
      this.resolvedPrefill = prefill ?? {};
      this.selectedTarget$.next(resolveStorageTarget(this.resolvedPrefill.storageTarget));
      this.prefillResolved$.next(true);
    });
    this.openSaveSubscription();
  }

  ngOnDestroy() {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as DownloadDocumentConfig;
    if (formValue?.storageTarget) {
      this.selectedTarget$.next(formValue.storageTarget);
    }
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: DownloadDocumentConfig): void {
    const valid = isDownloadDocumentConfigValid(formValue);
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
