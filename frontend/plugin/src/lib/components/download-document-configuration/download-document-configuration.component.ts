import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FunctionConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule} from '@valtimo/components';
import {BehaviorSubject, combineLatest, Observable, Subscription, take} from 'rxjs';
import {delay, startWith} from 'rxjs/operators';
import {DownloadDocumentConfig} from '../../models';

@Component({
  selector: 'epistola-download-document-configuration',
  templateUrl: './download-document-configuration.component.html',
  styleUrls: ['./download-document-configuration.component.scss'],
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule]
})
export class DownloadDocumentConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<DownloadDocumentConfig>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<DownloadDocumentConfig> = new EventEmitter<DownloadDocumentConfig>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<DownloadDocumentConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  safeDisabled$!: Observable<boolean>;

  ngOnInit(): void {
    this.safeDisabled$ = this.disabled$.pipe(
      startWith(true),
      delay(0)
    );
    this.openSaveSubscription();
  }

  ngOnDestroy() {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as DownloadDocumentConfig;
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: DownloadDocumentConfig): void {
    const valid = !!(
      formValue?.documentIdVariable &&
      formValue?.contentVariable
    );
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
