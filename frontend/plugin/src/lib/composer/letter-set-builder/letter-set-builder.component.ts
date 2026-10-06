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
import { PluginTranslatePipeModule, PluginTranslationService } from '@valtimo/plugin';
import { Subscription } from 'rxjs';
import { EpistolaComposerApiService } from '../composer-api.service';
import { CatalogInfo, TemplateInfo } from '../../models';

/** One letter on offer, as stored in the composer's settings. */
export interface OfferedTemplate {
  /**
   * Which catalog this letter lives in. The widget leaves it off — every letter it offers comes
   * from the one catalog picked above, which is stored on the set. A hand-written form may set it
   * per letter, which is what lets one picker offer letters from more than one catalog; the
   * backend reads it and falls back to the set's.
   */
  catalogId?: string;
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
  imports: [CommonModule, FormsModule, PluginTranslatePipeModule],
  selector: 'epistola-letter-set-builder-component',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="letter-set" data-testid="epistola-letter-set">
      <label class="field-label">
        {{ 'letterSetConnection' | pluginTranslate: pluginId | async }}
      </label>
      <select
        class="field-input"
        data-testid="epistola-letter-set-configuration"
        [ngModel]="value?.pluginConfigurationId || ''"
        (ngModelChange)="onConfigurationSelected($event)"
        [disabled]="disabled"
      >
        <option value="">
          {{
            (loadingConfigurations ? 'letterSetLoading' : 'letterSetChooseConnection')
              | pluginTranslate: pluginId
              | async
          }}
        </option>
        <option *ngFor="let configuration of configurations" [value]="configuration.id">
          {{ configuration.title
          }}{{ configuration.tenantId ? ' (' + configuration.tenantId + ')' : '' }}
        </option>
      </select>

      <label class="field-label">{{
        'letterSetCatalog' | pluginTranslate: pluginId | async
      }}</label>
      <select
        class="field-input"
        data-testid="epistola-letter-set-catalog"
        [ngModel]="value?.catalogId || ''"
        (ngModelChange)="onCatalogSelected($event)"
        [disabled]="disabled || !value?.pluginConfigurationId"
      >
        <option value="">
          {{
            (loadingCatalogs ? 'letterSetLoading' : 'letterSetChooseCatalog')
              | pluginTranslate: pluginId
              | async
          }}
        </option>
        <option *ngFor="let catalog of catalogs" [value]="catalog.id">{{ catalog.name }}</option>
      </select>

      <label class="field-label">{{
        'letterSetLetters' | pluginTranslate: pluginId | async
      }}</label>
      <div *ngIf="loadingTemplates" class="field-note">
        {{ 'letterSetLoadingTemplates' | pluginTranslate: pluginId | async }}
      </div>
      <div *ngIf="!loadingTemplates && !value?.catalogId" class="field-note">
        {{ 'letterSetCatalogFirst' | pluginTranslate: pluginId | async }}
      </div>
      <table *ngIf="!loadingTemplates && templates.length" class="letter-table">
        <tbody>
          <!--
            Both rows repeat together, so the settings panel stays inside the scope of the letter it
            belongs to. ngFor on the <tr> alone would repeat only that row, and the panel below it
            would reach for a template variable that is not in scope.
          -->
          <ng-container *ngFor="let template of templates">
            <tr>
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
                  [placeholder]="
                    ('letterSetLabelPlaceholder' | pluginTranslate: pluginId | async) || ''
                  "
                  [attr.data-testid]="'epistola-letter-set-label-' + template.id"
                  [disabled]="disabled || !isOffered(template.id)"
                  [ngModel]="labelOf(template.id)"
                  (ngModelChange)="setLabel(template.id, $event)"
                />
              </td>
              <td class="letter-settings-cell">
                <button
                  *ngIf="canConfigure(template.id)"
                  type="button"
                  class="letter-settings__toggle"
                  [attr.data-testid]="'epistola-letter-set-configure-' + template.id"
                  [attr.aria-expanded]="isSettingsOpen(template.id)"
                  [disabled]="disabled"
                  (click)="toggleSettings(template.id)"
                >
                  {{
                    (isSettingsOpen(template.id) ? 'letterSetConfigureClose' : 'letterSetConfigure')
                      | pluginTranslate: pluginId
                      | async
                  }}
                </button>
              </td>
            </tr>
            <tr *ngIf="isSettingsOpen(template.id)" class="letter-settings-row">
              <td colspan="4">
                <div
                  class="letter-settings"
                  [attr.data-testid]="'epistola-letter-set-settings-' + template.id"
                >
                  <label class="field-label" [attr.for]="'mapping-' + template.id">
                    {{ 'letterSetTemplateMapping' | pluginTranslate: pluginId | async }}
                  </label>
                  <p class="field-note">
                    {{ 'letterSetTemplateMappingTooltip' | pluginTranslate: pluginId | async }}
                  </p>
                  <textarea
                    class="field-input letter-settings__mapping"
                    rows="4"
                    [id]="'mapping-' + template.id"
                    [attr.data-testid]="'epistola-letter-set-mapping-' + template.id"
                    [disabled]="disabled"
                    [ngModel]="mappingOf(template.id)"
                    (ngModelChange)="setTemplateMapping(template.id, $event)"
                  ></textarea>
                </div>
              </td>
            </tr>
          </ng-container>
        </tbody>
      </table>
      <div
        *ngIf="missingTemplates.length"
        class="letter-missing"
        data-testid="epistola-letter-set-missing"
      >
        <p>{{ 'letterSetTemplateMissing' | pluginTranslate: pluginId | async }}</p>
        <ul>
          <li *ngFor="let template of missingTemplates">
            <strong>{{ template.label || template.templateId }}</strong>
            <code>{{ template.templateId }}</code>
            <button
              type="button"
              class="letter-missing__drop"
              [attr.data-testid]="'epistola-letter-set-drop-' + template.templateId"
              [disabled]="disabled"
              (click)="toggleTemplate(template.templateId, false)"
            >
              {{ 'letterSetTemplateMissingDrop' | pluginTranslate: pluginId | async }}
            </button>
          </li>
        </ul>
      </div>
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
      .letter-settings-cell {
        text-align: right;
        white-space: nowrap;
      }
      .letter-settings__toggle {
        background: none;
        border: none;
        padding: 0;
        color: #0f62fe;
        cursor: pointer;
        text-decoration: underline;
        font-size: 0.875rem;
      }
      .letter-settings {
        padding: 8px 12px 12px;
        background: #f4f4f4;
      }
      .letter-settings__mapping {
        width: 100%;
        font-family: monospace;
        font-size: 0.8125rem;
      }
      .letter-missing {
        margin-top: 8px;
        padding: 8px 12px;
        border-left: 3px solid #f1c21b;
        background: #fcf4d6;
        font-size: 0.875rem;
      }
      .letter-missing ul {
        margin: 4px 0 0;
        padding-left: 18px;
      }
      .letter-missing code {
        margin-left: 6px;
        opacity: 0.75;
      }
      .letter-missing__drop {
        margin-left: 8px;
        background: none;
        border: none;
        padding: 0;
        color: #0f62fe;
        cursor: pointer;
        text-decoration: underline;
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
  /**
   * Whether {@link templates} is an answer from Epistola rather than an absence of one.
   *
   * <p>Without it, "which stored letters are missing" cannot tell a catalog that no longer has a
   * letter from a catalog that could not be read — and telling an author every letter is gone
   * because Epistola was briefly unreachable is worse than saying nothing.
   */
  private templatesLoaded = false;

  /** The plugin whose translations this widget uses; constant, but templates need it bound. */
  readonly pluginId = 'epistola';

  private subscriptions: Subscription[] = [];
  private restored = false;

  constructor(
    private readonly composerApi: EpistolaComposerApiService,
    private readonly cdr: ChangeDetectorRef,
    private readonly pluginTranslationService: PluginTranslationService,
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

  /**
   * Letters this composer offers that the catalog no longer has.
   *
   * <p>The table above is drawn from what Epistola returns now, so a stored letter that has been
   * removed is not drawn at all: the configuration looks healthy while one of its letters is dead,
   * and the author finds out only when an employee opens the task and the composer refuses it.
   * These rows are that difference, shown where it can still be fixed.
   */
  /**
   * The letter whose settings are open, if any.
   *
   * <p>One at a time: the settings are per letter and the fields look identical, so two open panels
   * are an invitation to edit the wrong one.
   */
  private openSettingsFor: string | null = null;

  /** Whether this letter can be configured — only one that is actually offered. */
  canConfigure(templateId: string): boolean {
    return this.isOffered(templateId);
  }

  isSettingsOpen(templateId: string): boolean {
    return this.openSettingsFor === templateId;
  }

  toggleSettings(templateId: string): void {
    this.openSettingsFor = this.openSettingsFor === templateId ? null : templateId;
    this.cdr.markForCheck();
  }

  /** The fragment merged over the baseline mapping for this letter, as stored. */
  mappingOf(templateId: string): string {
    return (
      (this.value?.templates ?? []).find((template) => template.templateId === templateId)
        ?.dataMapping ?? ''
    );
  }

  /**
   * Store this letter's mapping fragment, or drop it when it is cleared.
   *
   * <p>Blank is dropped rather than stored: an empty fragment merges nothing over the baseline,
   * which is the same as having none, and storing one would make every saved letter look
   * configured.
   */
  setTemplateMapping(templateId: string, dataMapping: string): void {
    const trimmed = dataMapping?.trim();
    const next = (this.value?.templates ?? []).map((template) => {
      if (template.templateId !== templateId) {
        return template;
      }
      const { dataMapping: _dropped, ...rest } = template;
      return trimmed ? { ...rest, dataMapping: trimmed } : rest;
    });
    this.emit({
      pluginConfigurationId: this.value?.pluginConfigurationId ?? null,
      catalogId: this.value?.catalogId ?? null,
      templates: next,
    });
  }

  get missingTemplates(): OfferedTemplate[] {
    if (!this.templatesLoaded || this.loadingTemplates) {
      return [];
    }
    const available = new Set(this.templates.map((template) => template.id));
    return (this.value?.templates ?? []).filter((template) => !available.has(template.templateId));
  }

  private templateName(templateId: string): string {
    return this.templates.find((template) => template.id === templateId)?.name || templateId;
  }

  private loadConfigurations(): void {
    this.loadingConfigurations = true;
    this.subscriptions.push(
      this.composerApi.getConfigurations().subscribe({
        next: (configurations) => {
          this.configurations = configurations;
          this.loadingConfigurations = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loadingConfigurations = false;
          this.error = this.pluginTranslationService.instant(
            'letterSetConnectionsFailed',
            this.pluginId,
          );
          this.cdr.markForCheck();
        },
      }),
    );
  }

  private loadCatalogs(pluginConfigurationId: string): void {
    this.loadingCatalogs = true;
    this.subscriptions.push(
      this.composerApi.getCatalogs(pluginConfigurationId).subscribe({
        next: (catalogs) => {
          this.catalogs = catalogs;
          this.loadingCatalogs = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loadingCatalogs = false;
          this.error = this.pluginTranslationService.instant(
            'letterSetCatalogsFailed',
            this.pluginId,
          );
          this.cdr.markForCheck();
        },
      }),
    );
  }

  private loadTemplates(pluginConfigurationId: string, catalogId: string): void {
    this.loadingTemplates = true;
    this.subscriptions.push(
      this.composerApi.getTemplates(pluginConfigurationId, catalogId).subscribe({
        next: (templates) => {
          this.templates = templates;
          this.templatesLoaded = true;
          this.loadingTemplates = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loadingTemplates = false;
          this.templatesLoaded = false;
          this.error = this.pluginTranslationService.instant(
            'letterSetTemplatesFailed',
            this.pluginId,
          );
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
