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
  SimpleChanges,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FormioCustomComponent } from '@valtimo/components';
import { PluginTranslatePipeModule, PluginTranslationService } from '@valtimo/plugin';
import { Observable, of, Subject, Subscription } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { EpistolaComposerApiService } from '../composer-api.service';
import { CatalogInfo, EnvironmentInfo, LetterReader, TemplateInfo } from '../../models';
import {
  loadReleases,
  noReleases,
  ReleaseHint,
  releaseHintOf,
  ReleasesState,
} from '../../utils/release-hint';
import { catalogLabel, matchCatalogId, sortCatalogs } from '../../utils/catalog-ref';

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
  /**
   * The release of this letter's catalog to render, overriding the set's. Absent: the set's range
   * when the letter lives in the set's catalog, else the release the environment renders.
   */
  versionRange?: string;
}

/** What the builder produces: the whole "which letters, from where" half of the configuration. */
export interface LetterSet {
  pluginConfigurationId: string | null;
  /** Where every letter renders and is read from; absent for the configuration's default. */
  environmentId?: string | null;
  catalogId: string | null;
  /** The release of the set's catalog its letters render; absent for the environment's. */
  versionRange?: string | null;
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
        'letterSetEnvironment' | pluginTranslate: pluginId | async
      }}</label>
      <p class="field-note">
        {{ 'letterSetEnvironmentTooltip' | pluginTranslate: pluginId | async }}
      </p>
      <select
        class="field-input"
        data-testid="epistola-letter-set-environment"
        [ngModel]="value?.environmentId || ''"
        (ngModelChange)="onEnvironmentSelected($event)"
        [disabled]="disabled || !value?.pluginConfigurationId"
      >
        <option value="">
          {{
            (loadingEnvironments ? 'letterSetLoading' : 'letterSetEnvironmentDefault')
              | pluginTranslate: pluginId
              | async
          }}
        </option>
        <option *ngFor="let environment of environments" [value]="environment.id">
          {{ environment.name || environment.id }}
        </option>
        <!-- A stored environment the connection no longer lists stays visible, never silently swapped. -->
        <option *ngIf="environmentNotListed" [value]="value?.environmentId">
          {{ value?.environmentId }}
        </option>
      </select>

      <label class="field-label">{{
        'letterSetCatalog' | pluginTranslate: pluginId | async
      }}</label>
      <select
        class="field-input"
        data-testid="epistola-letter-set-catalog"
        [ngModel]="selectedCatalogId"
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
        <option *ngFor="let catalog of catalogs" [value]="catalog.id">
          {{ catalogLabel(catalog) }}
        </option>
        <option *ngIf="catalogNotInEnvironment" [value]="value?.catalogId">
          {{ value?.catalogId }}
        </option>
      </select>
      <div
        *ngIf="catalogNotInEnvironment"
        class="field-error"
        data-testid="epistola-letter-set-catalog-not-in-environment"
      >
        {{ 'letterSetCatalogNotInEnvironment' | pluginTranslate: pluginId | async }}
      </div>

      <ng-container *ngIf="value?.catalogId">
        <label class="field-label" for="epistola-letter-set-version-range-input">{{
          'versionRange' | pluginTranslate: pluginId | async
        }}</label>
        <p class="field-note">
          {{ 'letterSetVersionRangeTooltip' | pluginTranslate: pluginId | async }}
        </p>
        <input
          id="epistola-letter-set-version-range-input"
          type="text"
          class="field-input"
          data-testid="epistola-letter-set-version-range"
          list="epistola-letter-set-releases"
          autocomplete="off"
          [placeholder]="('versionRangePlaceholder' | pluginTranslate: pluginId | async) || ''"
          [disabled]="disabled"
          [ngModel]="value?.versionRange || ''"
          (ngModelChange)="onVersionRangeChange($event)"
        />
        <!-- The catalog's released versions, offered to pin one; a range is typed. -->
        <datalist id="epistola-letter-set-releases">
          <option *ngFor="let version of releaseVersions" [value]="version"></option>
        </datalist>
        <div
          *ngIf="releaseHint as hint"
          class="field-hint"
          [class.field-error]="hint.problem"
          data-testid="epistola-letter-set-version-range-hint"
          role="status"
        >
          {{ hint.key | pluginTranslate: pluginId | async }}
          <strong *ngIf="hint.version">{{ hint.version }}</strong>
        </div>
      </ng-container>

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
                  <label class="field-label" [attr.for]="'version-range-' + template.id">
                    {{ 'letterSetTemplateVersionRange' | pluginTranslate: pluginId | async }}
                  </label>
                  <p class="field-note">
                    {{ 'letterSetTemplateVersionRangeTooltip' | pluginTranslate: pluginId | async }}
                  </p>
                  <input
                    type="text"
                    class="field-input"
                    list="epistola-letter-set-releases"
                    autocomplete="off"
                    [id]="'version-range-' + template.id"
                    [attr.data-testid]="'epistola-letter-set-version-range-' + template.id"
                    [placeholder]="value?.versionRange || ''"
                    [disabled]="disabled"
                    [ngModel]="versionRangeOf(template.id)"
                    (ngModelChange)="setTemplateVersionRange(template.id, $event)"
                  />
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
      <!--
        What reads this letter. A statement, never a verdict: generating in another process is a
        supported design, so "none found" is information for the author, not an error. Rendered only
        once an answer has arrived, so a failed or skipped lookup shows nothing rather than claiming
        there is no reader.
      -->
      <div
        *ngIf="letterReaders as readers"
        class="field-hint"
        data-testid="epistola-letter-set-readers"
      >
        <ng-container *ngIf="readers.length; else noReaders">
          {{ 'letterSetReadBy' | pluginTranslate: pluginId | async }}
          <span *ngFor="let reader of readers; let last = last">
            <strong>{{ reader.activityId }}</strong>
            {{ 'letterSetReadByIn' | pluginTranslate: pluginId | async }}
            {{ reader.processDefinitionName }}{{ last ? '' : ', ' }}
          </span>
        </ng-container>
        <ng-template #noReaders>
          {{ 'letterSetReadByNothing' | pluginTranslate: pluginId | async }}
        </ng-template>
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
      /* Information, not a problem: deliberately quieter than .field-error. */
      .field-hint {
        color: #525252;
        font-size: 0.85rem;
        margin-top: 0.25rem;
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

  /**
   * The key of the composer being edited, handed over by the Formio wrapper from `root.data.key`.
   *
   * Changes while the dialog is open — that is the point: rename the key and the list of generate
   * tasks that read it goes empty in front of you.
   */
  @Input() composerKey = '';

  configurations: { id: string; title: string; tenantId: string | null }[] = [];
  catalogs: CatalogInfo[] = [];
  readonly catalogLabel = catalogLabel;

  /**
   * The stored catalog as the select lists it. A set saved against contract 1.x holds the
   * catalog's key alone, and catalogs are now listed as `registry/namespace/key`.
   */
  get selectedCatalogId(): string {
    if (this.catalogNotInEnvironment) {
      return this.value?.catalogId ?? '';
    }
    return this.matchedCatalogId() ?? '';
  }

  /**
   * True when the stored catalog is not among those the environment serves. Kept selected, with a
   * notice, rather than cleared: choosing an environment must not silently drop the letters an
   * author picked, and the author may mean to deploy the catalog there next.
   */
  get catalogNotInEnvironment(): boolean {
    return (
      this.catalogsLoaded &&
      !this.loadingCatalogs &&
      !!this.value?.catalogId &&
      this.matchedCatalogId() === null
    );
  }

  /** True when the stored environment is not one the connection lists (any more). */
  get environmentNotListed(): boolean {
    const environmentId = this.value?.environmentId;
    return (
      !!environmentId &&
      !this.loadingEnvironments &&
      !this.environments.some((environment) => environment.id === environmentId)
    );
  }

  /**
   * The listed catalog the stored one is, or null when none is. `matchCatalogId` hands back the
   * stored id unchanged when it matches nothing, so membership is checked here rather than assumed.
   */
  private matchedCatalogId(): string | null {
    const tenantId = this.configurations.find(
      (configuration) => configuration.id === this.value?.pluginConfigurationId,
    )?.tenantId;
    const matched = matchCatalogId(this.value?.catalogId, this.catalogs, tenantId);
    return this.catalogs.some((catalog) => catalog.id === matched) ? matched : null;
  }

  templates: TemplateInfo[] = [];
  environments: EnvironmentInfo[] = [];
  loadingEnvironments = false;
  /** The set's catalog's released versions, offered as suggestions for a range. */
  releaseVersions: string[] = [];
  /** What the set's range renders, as the range field explains it. */
  releaseHint: ReleaseHint | null = null;
  private catalogsLoaded = false;
  /**
   * What the letters and the releases are read for. Each change replaces the previous lookup, so a
   * slow answer for a range typed a moment ago can never overwrite the one for the range typed now.
   */
  private readonly templateScope$ = new Subject<TemplateScope | null>();
  private readonly releaseScope$ = new Subject<TemplateScope | null>();

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

  /** The generate tasks that read this composer's letter, or null while unknown. */
  letterReaders: LetterReader[] | null = null;
  /** The case type whose processes were searched, parsed from the builder's own URL. */
  private caseDefinitionKey: string | null = null;

  /** The plugin whose translations this widget uses; constant, but templates need it bound. */
  readonly pluginId = 'epistola';

  private subscriptions: Subscription[] = [];
  private restored = false;

  /**
   * Whether Formio has set `value` yet, which it does after construction.
   *
   * The connections are loaded from the constructor, so without this a form that already names a
   * connection could have it chosen for it — and *emitted* — before its own settings arrived,
   * replacing a configured letter set with an empty one just by opening the panel.
   */
  private valueArrived = false;

  constructor(
    private readonly composerApi: EpistolaComposerApiService,
    private readonly cdr: ChangeDetectorRef,
    private readonly pluginTranslationService: PluginTranslationService,
  ) {
    this.subscriptions.push(
      this.templateScope$.pipe(switchMap((scope) => this.fetchTemplates(scope))).subscribe(),
      this.releaseScope$
        .pipe(switchMap((scope) => this.fetchReleases(scope)))
        .subscribe((state) => {
          this.releaseHint = releaseHintOf(state);
          this.releaseVersions = state.resource.data?.versions ?? [];
          this.cdr.markForCheck();
        }),
    );
    this.loadConfigurations();
  }

  ngOnChanges(changes?: SimpleChanges): void {
    this.valueArrived = true;
    // Optional because Angular always passes it but a direct caller need not: the composer key is
    // what this reacts to, and an unspecified change set means "something did", so it asks again.
    if (!changes || changes['composerKey']) {
      this.loadLetterReaders();
    }
    // Formio sets the saved value after construction, so the cascade can only be restored once it
    // arrives — otherwise reopening the settings would show an empty catalog and no letters.
    if (!this.restored && this.value?.pluginConfigurationId) {
      this.restored = true;
      this.loadEnvironments(this.value.pluginConfigurationId);
      this.loadCatalogs(this.value.pluginConfigurationId, this.value.environmentId ?? null);
      this.refreshTemplates();
    }
    // Either order is possible: the connections may land before Formio sets the value or after.
    this.selectTheOnlyConnection();
  }

  /**
   * Ask which generate tasks read this letter, and say so.
   *
   * Deliberately a statement and never a verdict: an empty answer means none were found on this
   * case type, not that the configuration is wrong — generating in another process is a supported
   * design. An author who renames the key sees the line go empty, which is the moment the mistake
   * is made rather than hours later, when the only symptom is a letter that never arrived.
   *
   * Silent on failure. This is a hint beside a field; a broken lookup must not colour the panel red
   * or block saving a composer that is otherwise fine.
   */
  private loadLetterReaders(): void {
    this.letterReaders = null;
    const caseDefinitionKey = this.resolveCaseDefinitionKey();
    if (!caseDefinitionKey || !this.composerKey) {
      return;
    }
    this.subscriptions.push(
      this.composerApi.getLetterReaders(caseDefinitionKey, this.composerKey).subscribe({
        next: (readers) => {
          this.letterReaders = readers;
          this.cdr.markForCheck();
        },
        error: () => {
          this.letterReaders = null;
          this.cdr.markForCheck();
        },
      }),
    );
  }

  /**
   * The case type being authored, taken from the form builder's own URL
   * (`/case-management/case/{key}/version/{version}/forms/{id}`).
   *
   * There is no cleaner route here. The process-link configurator receives a case key through
   * Valtimo's process-link context, but the form builder is a plain page and a form definition does
   * not carry the case type it belongs to. Fails soft: no match means no hint, never an error.
   */
  private resolveCaseDefinitionKey(): string | null {
    if (this.caseDefinitionKey) {
      return this.caseDefinitionKey;
    }
    const path = typeof window === 'undefined' ? '' : (window.location?.pathname ?? '');
    const match = /\/case-management\/case\/([^/]+)\//.exec(path);
    this.caseDefinitionKey = match ? decodeURIComponent(match[1]) : null;
    return this.caseDefinitionKey;
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
    // The environment too: environments are listed per connection.
    this.emit({
      pluginConfigurationId: pluginConfigurationId || null,
      catalogId: null,
      templates: [],
    });
    this.catalogs = [];
    this.catalogsLoaded = false;
    this.templates = [];
    this.environments = [];
    this.refreshTemplates();
    if (pluginConfigurationId) {
      this.loadEnvironments(pluginConfigurationId);
      this.loadCatalogs(pluginConfigurationId, null);
    }
  }

  /**
   * Render in another environment. The catalog and the letters stay: the catalogs are listed again
   * for the new environment, and a catalog it does not serve is flagged rather than cleared.
   */
  onEnvironmentSelected(environmentId: string): void {
    this.patch({ environmentId: environmentId || null });
    if (this.value?.pluginConfigurationId) {
      this.loadCatalogs(this.value.pluginConfigurationId, this.value.environmentId ?? null);
    }
    this.refreshTemplates();
  }

  /** The release of the set's catalog its letters render; blank for the environment's. */
  onVersionRangeChange(versionRange: string): void {
    this.patch({ versionRange: versionRange?.trim() || null });
    this.refreshTemplates();
  }

  onCatalogSelected(catalogId: string): void {
    // A range is a range over one catalog's versions, so it goes with the catalog it was written for.
    this.patch({ catalogId: catalogId || null, versionRange: null, templates: [] });
    this.templates = [];
    this.refreshTemplates();
  }

  toggleTemplate(templateId: string, offered: boolean): void {
    const current = this.value?.templates ?? [];
    const next = offered
      ? [...current, { templateId, label: this.templateName(templateId) }]
      : current.filter((template) => template.templateId !== templateId);
    this.patch({ templates: next });
  }

  setLabel(templateId: string, label: string): void {
    const next = (this.value?.templates ?? []).map((template) =>
      template.templateId === templateId ? { ...template, label } : template,
    );
    this.patch({ templates: next });
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
    this.patch({ templates: next });
  }

  /** This letter's own range, as stored; blank when it renders the set's. */
  versionRangeOf(templateId: string): string {
    return (
      (this.value?.templates ?? []).find((template) => template.templateId === templateId)
        ?.versionRange ?? ''
    );
  }

  /** Pin this letter to its own release, or drop the override when it is cleared. */
  setTemplateVersionRange(templateId: string, versionRange: string): void {
    const trimmed = versionRange?.trim();
    const next = (this.value?.templates ?? []).map((template) => {
      if (template.templateId !== templateId) {
        return template;
      }
      const { versionRange: _dropped, ...rest } = template;
      return trimmed ? { ...rest, versionRange: trimmed } : rest;
    });
    this.patch({ templates: next });
  }

  get missingTemplates(): OfferedTemplate[] {
    if (!this.templatesLoaded || this.loadingTemplates) {
      return [];
    }
    const available = new Set(this.templates.map((template) => template.id));
    // The table lists the release the set renders. A letter pinned to a release of its own may
    // exist there and not here, so its absence from this list says nothing about it.
    return (this.value?.templates ?? []).filter(
      (template) => !template.versionRange && !available.has(template.templateId),
    );
  }

  private templateName(templateId: string): string {
    return this.templates.find((template) => template.id === templateId)?.name || templateId;
  }

  /**
   * Choose for the author when there is nothing to choose between.
   *
   * A normal install has one Epistola connection and one catalog, so the author met two dropdowns
   * holding one entry each before reaching a real decision. Both go through the ordinary selection
   * handler rather than assigning the field, so the choice is **emitted** and stored: a selection
   * that shows in the UI without being saved is this widget's classic silent failure.
   *
   * Only ever when nothing is chosen yet. A stored choice is never overwritten — including one
   * naming a connection that has since gone, which the author should see rather than have quietly
   * corrected to whichever connection happens to be the only one left.
   */
  private selectTheOnlyConnection(): void {
    if (
      this.valueArrived &&
      this.configurations.length === 1 &&
      !this.value?.pluginConfigurationId
    ) {
      this.onConfigurationSelected(this.configurations[0].id);
    }
  }

  private loadConfigurations(): void {
    this.loadingConfigurations = true;
    this.subscriptions.push(
      this.composerApi.getConfigurations().subscribe({
        next: (configurations) => {
          this.configurations = configurations;
          this.loadingConfigurations = false;
          this.cdr.markForCheck();
          this.selectTheOnlyConnection();
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

  private loadEnvironments(pluginConfigurationId: string): void {
    this.loadingEnvironments = true;
    this.subscriptions.push(
      this.composerApi.getEnvironments(pluginConfigurationId).subscribe({
        next: (environments) => {
          this.environments = environments;
          this.loadingEnvironments = false;
          this.cdr.markForCheck();
        },
        error: () => {
          // The default environment still works without the list, so this is not an error the
          // author has to act on: the select just offers the default.
          this.environments = [];
          this.loadingEnvironments = false;
          this.cdr.markForCheck();
        },
      }),
    );
  }

  private loadCatalogs(pluginConfigurationId: string, environmentId?: string | null): void {
    this.loadingCatalogs = true;
    this.subscriptions.push(
      this.composerApi.getCatalogs(pluginConfigurationId, environmentId).subscribe({
        next: (catalogs) => {
          this.catalogs = sortCatalogs(catalogs);
          this.catalogsLoaded = true;
          this.loadingCatalogs = false;
          this.cdr.markForCheck();
          // Safe without the value guard: catalogs are only ever loaded once a connection is
          // known, so by now the form's own settings have arrived.
          if (catalogs.length === 1 && !this.value?.catalogId) {
            this.onCatalogSelected(catalogs[0].id);
          }
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

  /** Read the letters, and what the range selects, for the set as it now is. */
  private refreshTemplates(): void {
    const configurationId = this.value?.pluginConfigurationId;
    const catalogId = this.value?.catalogId;
    const scope: TemplateScope | null =
      configurationId && catalogId
        ? {
            configurationId,
            catalogId,
            environmentId: this.value?.environmentId ?? null,
            versionRange: this.value?.versionRange ?? '',
          }
        : null;
    this.templateScope$.next(scope);
    this.releaseScope$.next(scope);
  }

  private fetchTemplates(scope: TemplateScope | null): Observable<unknown> {
    if (!scope) {
      this.templates = [];
      this.templatesLoaded = false;
      this.loadingTemplates = false;
      this.cdr.markForCheck();
      return of(null);
    }
    this.loadingTemplates = true;
    this.cdr.markForCheck();
    return this.composerApi
      .getTemplates(scope.configurationId, scope.catalogId, scope.environmentId, scope.versionRange)
      .pipe(
        map((templates) => {
          this.templates = templates;
          this.templatesLoaded = true;
          this.loadingTemplates = false;
          this.cdr.markForCheck();
          return templates;
        }),
        catchError((error: unknown) => {
          this.templates = [];
          this.loadingTemplates = false;
          this.templatesLoaded = false;
          const status = (error as { status?: number } | null)?.status;
          // A range no release satisfies (404) or that is no range at all (400) is what the range's
          // own hint says, so it is not also reported as a failure to read the catalog.
          if (!(scope.versionRange && (status === 404 || status === 400))) {
            this.error = this.pluginTranslationService.instant(
              'letterSetTemplatesFailed',
              this.pluginId,
            );
          }
          this.cdr.markForCheck();
          return of(null);
        }),
      );
  }

  private fetchReleases(scope: TemplateScope | null): Observable<ReleasesState> {
    if (!scope) {
      return of(noReleases(''));
    }
    return loadReleases(
      (catalogId, environment, range) =>
        this.composerApi.getReleases(
          scope.configurationId,
          catalogId,
          environment as string,
          range,
        ),
      scope.catalogId,
      scope.environmentId ?? undefined,
      scope.versionRange,
    );
  }

  /** Change part of the set, keeping the rest; an environment or range that is cleared is left out. */
  private patch(changes: Partial<LetterSet>): void {
    const next: LetterSet = {
      pluginConfigurationId: this.value?.pluginConfigurationId ?? null,
      catalogId: this.value?.catalogId ?? null,
      templates: this.value?.templates ?? [],
      ...(this.value?.environmentId ? { environmentId: this.value.environmentId } : {}),
      ...(this.value?.versionRange ? { versionRange: this.value.versionRange } : {}),
      ...changes,
    };
    if (!next.environmentId) delete next.environmentId;
    if (!next.versionRange) delete next.versionRange;
    this.emit(next);
  }

  private emit(value: LetterSet): void {
    this.value = value;
    this.valueChange.emit(value);
    this.cdr.markForCheck();
  }
}

/** What a set's letters and releases are read for. */
interface TemplateScope {
  configurationId: string;
  catalogId: string;
  environmentId: string | null;
  versionRange: string;
}
