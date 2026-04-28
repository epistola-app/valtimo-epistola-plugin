import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaDocumentPreviewComponent } from './epistola-document-preview.component';
import { OverrideMapping } from '../override-builder/override-builder.component';

export const EPISTOLA_DOCUMENT_PREVIEW_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-document-preview',
  selector: 'epistola-document-preview-element',
  title: 'Epistola Document Preview',
  group: 'basic',
  icon: 'file-pdf-o',
  emptyValue: null,
  fieldOptions: ['label', 'processDefinitionKey', 'sourceActivityId', 'overrideMapping'],
  editForm: () => ({
    components: [
      {
        type: 'epistola-process-link-selector',
        key: 'processLinkSelection',
        label: 'Process Link',
        weight: 10,
      },
      {
        type: 'epistola-override-builder',
        key: 'overrideMapping',
        label: 'Input Overrides',
        weight: 20,
      },
    ],
  }),
};

/**
 * Expand dot-notation keys into nested objects.
 * e.g. { "beslissing.tekst": "value" } → { beslissing: { tekst: "value" } }
 */
function expandDotNotation(flat: Record<string, any>): Record<string, any> {
  const result: Record<string, any> = {};
  for (const [key, value] of Object.entries(flat)) {
    const parts = key.split('.');
    let current = result;
    for (let i = 0; i < parts.length - 1; i++) {
      if (!current[parts[i]] || typeof current[parts[i]] !== 'object') {
        current[parts[i]] = {};
      }
      current = current[parts[i]];
    }
    current[parts[parts.length - 1]] = value;
  }
  return result;
}

/**
 * Given an override mapping (scope → { inputPath → formFieldKey })
 * and form data, produce the inputOverrides object for the backend.
 */
function computeInputOverrides(
  mapping: OverrideMapping,
  formData: Record<string, any>,
): Record<string, any> {
  const result: Record<string, any> = {};
  for (const [scope, fields] of Object.entries(mapping)) {
    if (scope !== 'doc' && scope !== 'pv') continue;
    const flatOverrides: Record<string, any> = {};
    for (const [inputPath, formFieldKey] of Object.entries(fields)) {
      const value = formData[formFieldKey];
      if (value !== undefined) {
        flatOverrides[inputPath] = value;
      }
    }
    if (Object.keys(flatOverrides).length > 0) {
      result[scope] = expandDotNotation(flatOverrides);
    }
  }
  return result;
}

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

    attach(element: HTMLElement) {
      const result = super.attach(element);

      // Sync processLinkSelection → processDefinitionKey + sourceActivityId
      if (this.component?.processLinkSelection) {
        const sel = this.component.processLinkSelection;
        this.component.processDefinitionKey = sel.processDefinitionKey || '';
        this.component.sourceActivityId = sel.sourceActivityId || '';
        if (this._customAngularElement) {
          this._customAngularElement['processDefinitionKey'] = this.component.processDefinitionKey;
          this._customAngularElement['sourceActivityId'] = this.component.sourceActivityId;
        }
      }

      // Listen to form changes and compute input overrides from the mapping
      if (this.root && this.component?.overrideMapping && !this._changeListenerAttached) {
        this._changeListenerAttached = true;
        this.root.on('change', () => {
          this._computeAndSetOverrides();
        });
        // Compute initial value
        this._computeAndSetOverrides();
      }

      return result;
    }

    private _computeAndSetOverrides() {
      if (this._debounceTimer) {
        clearTimeout(this._debounceTimer);
      }
      this._debounceTimer = setTimeout(() => {
        const mapping = this.component?.overrideMapping;
        const formData = this.root?.data;
        if (mapping && formData) {
          const overrides = computeInputOverrides(mapping, formData);
          if (Object.keys(overrides).length > 0) {
            this.setValue(overrides);
          }
        }
      }, 1500);
    }
  }

  // Re-register with the extended class
  Formio.Components.setComponent(EPISTOLA_DOCUMENT_PREVIEW_OPTIONS.type, PreviewWithOverrides);
}
