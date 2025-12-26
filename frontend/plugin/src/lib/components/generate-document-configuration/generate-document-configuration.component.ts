import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FunctionConfigurationComponent, PluginTranslatePipeModule} from '@valtimo/plugin';
import {FormModule, FormOutput, InputModule, SelectModule, SelectItem} from '@valtimo/components';
import {BehaviorSubject, combineLatest, Observable, Subscription, take} from 'rxjs';
import {GenerateDocumentConfig} from '../../models';

@Component({
  selector: 'epistola-generate-document-configuration',
  templateUrl: './generate-document-configuration.component.html',
  styleUrls: ['./generate-document-configuration.component.scss'],
  standalone: true,
  imports: [CommonModule, PluginTranslatePipeModule, FormModule, InputModule, SelectModule]
})
export class GenerateDocumentConfigurationComponent
  implements FunctionConfigurationComponent, OnInit, OnDestroy
{
  @Input() save$!: Observable<void>;
  @Input() disabled$!: Observable<boolean>;
  @Input() pluginId!: string;
  @Input() prefillConfiguration$!: Observable<GenerateDocumentConfig>;

  @Output() valid: EventEmitter<boolean> = new EventEmitter<boolean>();
  @Output() configuration: EventEmitter<GenerateDocumentConfig> = new EventEmitter<GenerateDocumentConfig>();

  outputFormatOptions: SelectItem[] = [
    {id: 'PDF', text: 'PDF'},
    {id: 'HTML', text: 'HTML'}
  ];

  private saveSubscription!: Subscription;
  private readonly formValue$ = new BehaviorSubject<GenerateDocumentConfig | null>(null);
  private readonly valid$ = new BehaviorSubject<boolean>(false);

  ngOnInit(): void {
    this.openSaveSubscription();
  }

  ngOnDestroy() {
    this.saveSubscription?.unsubscribe();
  }

  formValueChange(formOutput: FormOutput): void {
    const formValue = formOutput as unknown as GenerateDocumentConfig;
    this.formValue$.next(formValue);
    this.handleValid(formValue);
  }

  private handleValid(formValue: GenerateDocumentConfig): void {
    const valid = !!(
      formValue?.templateId &&
      formValue?.outputFormat &&
      formValue?.filename &&
      formValue?.resultProcessVariable
    );
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
