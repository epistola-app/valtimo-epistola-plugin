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

import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaDocumentPreviewComponent } from './epistola-document-preview.component';
import { computeInputOverrides } from './preview-utils';
import { readPrefilledTaskId, PREFILLED_TASK_ID_CARRIER } from '../../services/prefilled-task-id';

/** Default debounce for the auto-refresh, in milliseconds. */
const DEFAULT_REFRESH_DEBOUNCE_MS = 1500;

export const EPISTOLA_DOCUMENT_PREVIEW_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-document-preview',
  selector: 'epistola-document-preview-element',
  title: 'Epistola Document Preview',
  group: 'basic',
  icon: 'file-pdf-o',
  emptyValue: null,
  fieldOptions: ['label', 'processDefinitionKey', 'sourceActivityId', 'overrideMapping'],
  // Embed the hidden task-id carrier so dropping this component is enough — no separate
  // field for the author to add. Valtimo prefills it server-side via the epistola: resolver.
  schema: { components: [PREFILLED_TASK_ID_CARRIER] },
  editForm: () => ({
    components: [
      {
        type: 'epistola-process-link-selector',
        key: 'processLinkSelection',
        label: 'Process Link',
        weight: 10,
        validate: { required: true },
      },
      {
        type: 'epistola-override-builder',
        key: 'overrideMapping',
        label: 'Input Overrides',
        weight: 20,
      },
      {
        type: 'checkbox',
        key: 'autoRefresh',
        label: 'Auto-refresh preview as the form is filled in',
        tooltip:
          'When on, the preview refreshes automatically while the form is edited — debounced, and only when a field loses focus, not on every keystroke. Turn off to refresh only with the Refresh button.',
        defaultValue: true,
        weight: 30,
      },
      {
        type: 'number',
        key: 'refreshDebounceMs',
        label: 'Auto-refresh debounce (ms)',
        tooltip:
          'How long to wait after the last change before refreshing. Higher values feel calmer; lower values feel more responsive.',
        defaultValue: DEFAULT_REFRESH_DEBOUNCE_MS,
        weight: 40,
        conditional: { show: true, when: 'autoRefresh', eq: 'true' },
      },
    ],
  }),
};

export function registerEpistolaDocumentPreviewComponent(injector: Injector): void {
  if (customElements.get(EPISTOLA_DOCUMENT_PREVIEW_OPTIONS.selector)) {
    return;
  }

  // Register the base component (Angular element + Formio component class)
  registerCustomFormioComponent(
    EPISTOLA_DOCUMENT_PREVIEW_OPTIONS,
    EpistolaDocumentPreviewComponent,
    injector,
  );

  // Get the Formio Components registry and the registered base class
  const Formio = (window as any).Formio;
  if (!Formio?.Components) return;

  const BasePreviewComponent = Formio.Components.components[EPISTOLA_DOCUMENT_PREVIEW_OPTIONS.type];
  if (!BasePreviewComponent) return;

  // Extend the base class to listen for form data changes and compute input overrides
  class PreviewWithOverrides extends BasePreviewComponent {
    private _debounceTimer: any = null;
    private _changeListenerAttached = false;
    private _changeHandler: (() => void) | null = null;
    private _blurHandler: (() => void) | null = null;
    private _blurTarget: HTMLElement | null = null;
    private _destroyed = false;
    private _debounceMs = DEFAULT_REFRESH_DEBOUNCE_MS;
    // Serialized form of the last value pushed via setValue, so we skip re-rendering
    // the preview when a change recomputes to the same overrides (e.g. typing in a
    // field that isn't part of the mapping). undefined = nothing pushed yet.
    private _lastPushedJson: string | undefined = undefined;

    attach(element: HTMLElement) {
      // Formio detaches and re-attaches components on every redraw — not only at
      // teardown — so a re-attach means the component is alive again. Clear the
      // destroyed flag here; it only stays set when detach() is the final call
      // (genuine teardown, e.g. task completion), which is what suppresses the
      // post-submit preview.
      this._destroyed = false;

      // Bidirectional sync between processLinkSelection object and separate properties.
      // The editForm uses processLinkSelection (single field), while the component
      // config and Angular inputs use processDefinitionKey + sourceActivityId.
      if (this.component?.processLinkSelection) {
        const sel = this.component.processLinkSelection;
        this.component.processDefinitionKey = sel.processDefinitionKey || '';
        this.component.sourceActivityId = sel.sourceActivityId || '';
      } else if (this.component?.processDefinitionKey && this.component?.sourceActivityId) {
        this.component.processLinkSelection = {
          processDefinitionKey: this.component.processDefinitionKey,
          sourceActivityId: this.component.sourceActivityId,
        };
      }

      const result = super.attach(element);

      if (this._customAngularElement) {
        this._customAngularElement['processDefinitionKey'] =
          this.component.processDefinitionKey || '';
        this._customAngularElement['sourceActivityId'] = this.component.sourceActivityId || '';
        // Forward the server-prefilled task id (epistola: value resolver) so the
        // component authorizes against the exact task in every Valtimo task-open flow.
        const prefilledTaskId = readPrefilledTaskId(this.root);
        if (prefilledTaskId) {
          this._customAngularElement['taskInstanceId'] = prefilledTaskId;
        }
      }

      // Compute input overrides from the mapping. Always paint once from the
      // current/pre-filled data; only wire up the live listeners when auto-refresh
      // is enabled (the default).
      if (this.root && this.component?.overrideMapping && !this._changeListenerAttached) {
        this._changeListenerAttached = true;
        this._debounceMs = this._resolveDebounceMs();

        // Compute the initial overrides immediately (no debounce) so a pre-filled
        // form paints its preview without the debounce delay. This runs even when
        // auto-refresh is off, so the preview still shows once on open.
        this._computeAndSetOverrides(true);

        if (this.component.autoRefresh !== false) {
          // Debounced recompute on any form change — collapses bursts of edits and,
          // together with the dedup in _computeAndSetOverrides, only re-renders when
          // the mapped data actually changes.
          this._changeHandler = () => this._computeAndSetOverrides();
          this.root.on('change', this._changeHandler);

          // Flush immediately when a field loses focus. `focusout` bubbles (unlike
          // `blur`), so one listener on the form root catches every input — and it
          // fires on blur rather than on each keystroke, which is what keeps the
          // refresh from feeling hectic.
          const formEl: HTMLElement | undefined = this.root?.element;
          if (formEl?.addEventListener) {
            this._blurHandler = () => this._computeAndSetOverrides(true);
            formEl.addEventListener('focusout', this._blurHandler);
            this._blurTarget = formEl;
          }
        }
      }

      return result;
    }

    // Tear down the change listener and any pending debounce so a preview is never
    // fired after the form is unmounted (e.g. on task completion). Without this the
    // 1.5s debounce can outlive submit and POST /preview with reset/incomplete data,
    // which Epistola rejects with a 400.
    detach() {
      this._destroyed = true;
      if (this._debounceTimer) {
        clearTimeout(this._debounceTimer);
        this._debounceTimer = null;
      }
      if (this._changeHandler && this.root?.off) {
        this.root.off('change', this._changeHandler);
        this._changeHandler = null;
      }
      if (this._blurHandler && this._blurTarget?.removeEventListener) {
        this._blurTarget.removeEventListener('focusout', this._blurHandler);
        this._blurHandler = null;
        this._blurTarget = null;
      }
      this._changeListenerAttached = false;
      this._lastPushedJson = undefined;
      return super.detach();
    }

    private _computeAndSetOverrides(immediate = false) {
      if (this._debounceTimer) {
        clearTimeout(this._debounceTimer);
      }
      this._debounceTimer = setTimeout(
        async () => {
          // Skip if the form is being/has been submitted or the component is gone —
          // those previews would run with incomplete/reset data and 400 from Epistola.
          if (this._destroyed || this.root?.submitting || this.root?.submitted) {
            return;
          }
          const mapping = this.component?.overrideMapping;
          const formData = this.root?.data;
          if (!mapping || !formData) {
            return;
          }
          // computeInputOverrides evaluates a JSONata expression (async). Re-check
          // the submit/teardown guards after the await — they can flip while the
          // promise is in flight.
          const overrides = await computeInputOverrides(mapping, formData);
          if (this._destroyed || this.root?.submitting || this.root?.submitted) {
            return;
          }
          // Push null when there's nothing usable yet so the component reverts to
          // its "complete the form" placeholder instead of keeping a stale preview.
          const next = Object.keys(overrides).length > 0 ? overrides : null;
          // Dedup: only push (and re-render) when the computed overrides actually
          // changed since the last push. A change/blur that doesn't affect the
          // mapped data recomputes to the same value and is dropped here.
          const nextJson = JSON.stringify(next);
          if (nextJson === this._lastPushedJson) {
            return;
          }
          this._lastPushedJson = nextJson;
          this.setValue(next);
        },
        immediate ? 0 : this._debounceMs,
      );
    }

    /**
     * Resolve the configured auto-refresh debounce (ms), falling back to the
     * default for missing or non-numeric/negative values.
     */
    private _resolveDebounceMs(): number {
      const configured = Number(this.component?.refreshDebounceMs);
      return Number.isFinite(configured) && configured >= 0
        ? configured
        : DEFAULT_REFRESH_DEBOUNCE_MS;
    }
  }

  // Re-register with the extended class
  Formio.Components.setComponent(EPISTOLA_DOCUMENT_PREVIEW_OPTIONS.type, PreviewWithOverrides);
}
