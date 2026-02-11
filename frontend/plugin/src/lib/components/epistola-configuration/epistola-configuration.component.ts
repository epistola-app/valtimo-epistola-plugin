import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PluginConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule} from '@valtimo/components';
import {BehaviorSubject, combineLatest, Observable, Subscription, take} from 'rxjs';
import {delay, startWith} from 'rxjs/operators';
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

  /** Epistola slug pattern: lowercase alphanumeric with hyphens, no leading/trailing hyphens. */
  private static readonly SLUG_PATTERN = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<EpistolaPluginConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  safeDisabled$!: Observable<boolean>;

  ngOnInit(): void {
    // Wrap disabled$ with startWith and delay to prevent NG0100 ExpressionChangedAfterItHasBeenCheckedError
    // The disabled$ observable from Valtimo's plugin framework can emit value changes after Angular's
    // change detection cycle completes, causing the error.
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
    const formValue = formOutput as unknown as EpistolaPluginConfig;
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: EpistolaPluginConfig): void {
    const valid = !!(
      formValue?.configurationTitle &&
      formValue?.baseUrl &&
      formValue?.apiKey &&
      formValue?.tenantId &&
      this.isValidSlug(formValue.tenantId, 3, 63) &&
      this.isValidOptionalSlug(formValue.defaultEnvironmentId, 3, 30)
    );
    this.valid$.next(valid);
    this.valid.emit(valid);
  }

  private isValidSlug(value: string, minLength: number, maxLength: number): boolean {
    return (
      value.length >= minLength &&
      value.length <= maxLength &&
      EpistolaConfigurationComponent.SLUG_PATTERN.test(value)
    );
  }

  private isValidOptionalSlug(value: string | undefined, minLength: number, maxLength: number): boolean {
    if (!value) {
      return true;
    }
    return this.isValidSlug(value, minLength, maxLength);
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
