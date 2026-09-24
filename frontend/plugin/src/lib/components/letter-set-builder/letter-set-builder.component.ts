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
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FormioCustomComponent } from '@valtimo/components';
import { Subscription } from 'rxjs';
import { EpistolaPluginService } from '../../services';
import { CatalogInfo, TemplateInfo } from '../../models';

/** One letter on offer, as stored in the composer's settings. */
export interface OfferedTemplate {
  templateId: string;
  label?: string;
  dataMapping?: string;
}

/** What the builder produces: the whole "which letters, from where" half of the configuration. */
export interface LetterSet {
  pluginConfigurationId: string | null;
  catalogId: string | null;
  templates: OfferedTemplate[];
}

/**
 * The settings widget behind a letter composer: pick a connection, pick a catalog, tick the
 * letters to offer.
 *
 * <p>An author should not have to paste a configuration UUID or know a template's slug. The three
 * choices cascade, because a catalog only means something within a connection and a template only
 * within a catalog — changing either resets what no longer applies rather than leaving a stale id
 * behind that fails at runtime.
 */
@Component({
  standalone: true,
  imports: [CommonModule, FormsModule],
  selector: 'epistola-letter-set-builder-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="letter-set" data-testid="epistola-letter-set">
      <label class="field-label">Epistola connection</label>
      <select
        class="field-input"
        data-testid="epistola-letter-set-configuration"
        [ngModel]="value?.pluginConfigurationId || ''"
        (ngModelChange)="onConfigurationSelected($event)"
        [disabled]="disabled"
      >
        <option value="">
          {{ loadingConfigurations ? 'Loading…' : '— choose a connection —' }}
        </option>
        <option *ngFor="let configuration of configurations" [value]="configuration.id">
          {{ configuration.title
          }}{{ configuration.tenantId ? ' (' + configuration.tenantId + ')' : '' }}
        </option>
      </select>

      <label class="field-label">Catalog</label>
      <select
        class="field-input"
        data-testid="epistola-letter-set-catalog"
        [ngModel]="value?.catalogId || ''"
        (ngModelChange)="onCatalogSelected($event)"
        [disabled]="disabled || !value?.pluginConfigurationId"
      >
        <option value="">{{ loadingCatalogs ? 'Loading…' : '— choose a catalog —' }}</option>
        <option *ngFor="let catalog of catalogs" [value]="catalog.id">{{ catalog.name }}</option>
      </select>

      <label class="field-label">Letters on offer</label>
      <div *ngIf="loadingTemplates" class="field-note">Loading templates…</div>
      <div *ngIf="!loadingTemplates && !value?.catalogId" class="field-note">
        Choose a catalog to see its templates.
      </div>
      <table *ngIf="!loadingTemplates && templates.length" class="letter-table">
        <tbody>
          <tr *ngFor="let template of templates">
            <td class="letter-tick">
              <input
                type="checkbox"
                [attr.data-testid]="'epistola-letter-set-offer-' + template.id"
                [checked]="isOffered(template.id)"
                (change)="toggleTemplate(template.id, $any($event.target).checked)"
                [disabled]="disabled"
              />
            </td>
            <td class="letter-name">{{ template.name || template.id }}</td>
            <td class="letter-label">
              <input
                type="text"
                class="field-input"
                placeholder="Label for the employee"
                [attr.data-testid]="'epistola-letter-set-label-' + template.id"
                [disabled]="disabled || !isOffered(template.id)"
                [ngModel]="labelOf(template.id)"
                (ngModelChange)="setLabel(template.id, $event)"
              />
            </td>
          </tr>
        </tbody>
      </table>
      <div *ngIf="error" class="field-error" data-testid="epistola-letter-set-error">
        {{ error }}
      </div>
    </div>
  `,
  styles: [
    `
      .letter-set {
        margin-bottom: 0.5rem;
      }
      .field-label {
        display: block;
        font-weight: 600;
        font-size: 0.85rem;
        color: #495057;
        margin: 0.5rem 0 0.25rem;
      }
      .field-input {
        width: 100%;
        border: 1px solid #ced4da;
        border-radius: 4px;
        padding: 0.35rem;
      }
      .field-note {
        color: #6c757d;
        font-size: 0.85rem;
      }
      .field-error {
        color: #dc3545;
        font-size: 0.85rem;
      }
      .letter-table {
        width: 100%;
        border-collapse: collapse;
      }
      .letter-tick {
        width: 2rem;
      }
      .letter-name {
        padding-right: 0.5rem;
        white-space: nowrap;
      }
      .letter-label {
        width: 50%;
      }
    `,
  ],
})
export class EpistolaLetterSetBuilderComponent
  implements FormioCustomComponent<LetterSet | null>, OnChanges, OnDestroy
{
  @Input() value: LetterSet | null = null;
  @Output() valueChange = new EventEmitter<LetterSet | null>();
  @Input() disabled = false;
  @Input() label?: string;

  configurations: { id: string; title: string; tenantId: string | null }[] = [];
  catalogs: CatalogInfo[] = [];
  templates: TemplateInfo[] = [];

  loadingConfigurations = false;
  loadingCatalogs = false;
  loadingTemplates = false;
  error: string | null = null;

  private subscriptions: Subscription[] = [];
  private restored = false;

  constructor(
    private readonly epistolaPluginService: EpistolaPluginService,
    private readonly cdr: ChangeDetectorRef,
  ) {
    this.loadConfigurations();
  }

  ngOnChanges(): void {
    // Formio sets the saved value after construction, so the cascade can only be restored once it
    // arrives — otherwise reopening the settings would show an empty catalog and no letters.
    if (this.restored || !this.value?.pluginConfigurationId) {
      return;
    }
    this.restored = true;
    this.loadCatalogs(this.value.pluginConfigurationId);
    if (this.value.catalogId) {
      this.loadTemplates(this.value.pluginConfigurationId, this.value.catalogId);
    }
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach((subscription) => subscription.unsubscribe());
  }

  isOffered(templateId: string): boolean {
    return (this.value?.templates ?? []).some((template) => template.templateId === templateId);
  }

  labelOf(templateId: string): string {
    return (
      (this.value?.templates ?? []).find((template) => template.templateId === templateId)?.label ??
      ''
    );
  }

  onConfigurationSelected(pluginConfigurationId: string): void {
    this.restored = true;
    // A catalog and its templates only mean something within one connection, so both are cleared
    // rather than left pointing at ids the new connection may not have.
    this.emit({
      pluginConfigurationId: pluginConfigurationId || null,
      catalogId: null,
      templates: [],
    });
    this.catalogs = [];
    this.templates = [];
    if (pluginConfigurationId) {
      this.loadCatalogs(pluginConfigurationId);
    }
  }

  onCatalogSelected(catalogId: string): void {
    this.emit({
      pluginConfigurationId: this.value?.pluginConfigurationId ?? null,
      catalogId: catalogId || null,
      templates: [],
    });
    this.templates = [];
    if (catalogId && this.value?.pluginConfigurationId) {
      this.loadTemplates(this.value.pluginConfigurationId, catalogId);
    }
  }

  toggleTemplate(templateId: string, offered: boolean): void {
    const current = this.value?.templates ?? [];
    const next = offered
      ? [...current, { templateId, label: this.templateName(templateId) }]
      : current.filter((template) => template.templateId !== templateId);
    this.emit({
      pluginConfigurationId: this.value?.pluginConfigurationId ?? null,
      catalogId: this.value?.catalogId ?? null,
      templates: next,
    });
  }

  setLabel(templateId: string, label: string): void {
    const next = (this.value?.templates ?? []).map((template) =>
      template.templateId === templateId ? { ...template, label } : template,
    );
    this.emit({
      pluginConfigurationId: this.value?.pluginConfigurationId ?? null,
      catalogId: this.value?.catalogId ?? null,
      templates: next,
    });
  }

  private templateName(templateId: string): string {
    return this.templates.find((template) => template.id === templateId)?.name || templateId;
  }

  private loadConfigurations(): void {
    this.loadingConfigurations = true;
    this.subscriptions.push(
      this.epistolaPluginService.getConfigurations().subscribe({
        next: (configurations) => {
          this.configurations = configurations;
          this.loadingConfigurations = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loadingConfigurations = false;
          this.error = 'Could not load the Epistola connections.';
          this.cdr.markForCheck();
        },
      }),
    );
  }

  private loadCatalogs(pluginConfigurationId: string): void {
    this.loadingCatalogs = true;
    this.subscriptions.push(
      this.epistolaPluginService.getCatalogs(pluginConfigurationId).subscribe({
        next: (catalogs) => {
          this.catalogs = catalogs;
          this.loadingCatalogs = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loadingCatalogs = false;
          this.error = 'Could not load the catalogs of this connection.';
          this.cdr.markForCheck();
        },
      }),
    );
  }

  private loadTemplates(pluginConfigurationId: string, catalogId: string): void {
    this.loadingTemplates = true;
    this.subscriptions.push(
      this.epistolaPluginService.getTemplates(pluginConfigurationId, catalogId).subscribe({
        next: (templates) => {
          this.templates = templates;
          this.loadingTemplates = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loadingTemplates = false;
          this.error = 'Could not load the templates of this catalog.';
          this.cdr.markForCheck();
        },
      }),
    );
  }

  private emit(value: LetterSet): void {
    this.value = value;
    this.valueChange.emit(value);
    this.cdr.markForCheck();
  }
}
