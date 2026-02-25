import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FunctionConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule} from '@valtimo/components';
import {BehaviorSubject, combineLatest, Observable, Subscription, take} from 'rxjs';
import {delay, startWith} from 'rxjs/operators';
import {CheckJobStatusConfig} from '../../models';

@Component({
  selector: 'epistola-check-job-status-configuration',
  templateUrl: './check-job-status-configuration.component.html',
  styleUrls: ['./check-job-status-configuration.component.scss'],
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule]
})
export class CheckJobStatusConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<CheckJobStatusConfig>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<CheckJobStatusConfig> = new EventEmitter<CheckJobStatusConfig>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<CheckJobStatusConfig | null>(null);
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
    const formValue = formOutput as unknown as CheckJobStatusConfig;
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: CheckJobStatusConfig): void {
    const valid = !!(
      formValue?.requestIdVariable &&
      formValue?.statusVariable
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
