import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule} from '@valtimo/components';
import {BehaviorSubject, combineLatest, Observable, Subscription, take} from 'rxjs';
import {EpistolaPluginConfig} from '../../models';

@Component({
  selector: 'epistola-configuration',
  templateUrl: './epistola-configuration.component.html',
  styleUrls: ['./epistola-configuration.component.scss'],
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule]
})
export class EpistolaConfigurationComponent
  implements PluginConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<EpistolaPluginConfig>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<EpistolaPluginConfig> = new EventEmitter<EpistolaPluginConfig>();

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<EpistolaPluginConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  ngOnInit(): void {
    this.openSaveSubscription();
  }

  ngOnDestroy() {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as EpistolaPluginConfig;
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: EpistolaPluginConfig): void {
    const valid = !!(formValue?.configurationTitle && formValue?.tenantId);
    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private openSaveSubscription(): void {
    this.saveSubscription = this.save$?.subscribe(() => {
      combineLatest([this.formValue$, this.valid$])
        .pipe(take(1))
        .subscribe(([formValue, valid]) => {
          if (valid) {
            this.configuration.emit(formValue);
          }
        });
    });
  }
}
