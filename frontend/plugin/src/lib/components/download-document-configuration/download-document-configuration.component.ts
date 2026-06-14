import { Component, EventEmitter, Input, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FunctionConfigurationComponent, PluginTranslatePipeModule } from '@valtimo/plugin';
import { FormModule, FormOutput, InputModule, SelectItem, SelectModule } from '@valtimo/components';
import { BehaviorSubject, combineLatest, Observable, of, Subscription, take } from 'rxjs';
import { delay, startWith } from 'rxjs/operators';
import { DownloadDocumentConfig } from '../../models';

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
  @Output() configuration: EventEmitter<DownloadDocumentConfig> =
    new EventEmitter<DownloadDocumentConfig>();

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
  readonly defaultStorageTarget = 'TEMPORARY_RESOURCE';

  /** Drives which output-variable field is shown (resource id vs inline content). */
  readonly selectedTarget$ = new BehaviorSubject<string>(this.defaultStorageTarget);

  ngOnInit(): void {
    this.safeDisabled$ = this.disabled$.pipe(startWith(true), delay(0));
    const prefill$ = this.prefillConfiguration$ ?? of({} as DownloadDocumentConfig);
    prefill$.pipe(take(1)).subscribe((prefill) => {
      this.resolvedPrefill = prefill ?? {};
      this.selectedTarget$.next(this.resolvedPrefill.storageTarget ?? this.defaultStorageTarget);
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
    const target = formValue?.storageTarget ?? this.defaultStorageTarget;
    const outputSet =
      target === 'PROCESS_VARIABLE'
        ? !!formValue?.contentVariable
        : !!formValue?.resourceIdVariable;
    const valid = !!(formValue?.documentVariable && target && outputSet);
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
