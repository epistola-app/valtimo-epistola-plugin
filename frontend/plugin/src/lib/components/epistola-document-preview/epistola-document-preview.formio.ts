import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaDocumentPreviewComponent } from './epistola-document-preview.component';
import { computeInputOverrides } from './preview-utils';

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
        validate: { required: true },
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
