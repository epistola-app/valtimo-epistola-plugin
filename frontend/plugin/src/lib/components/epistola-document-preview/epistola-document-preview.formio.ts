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
    private _changeHandler: (() => void) | null = null;
    private _destroyed = false;

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
      }

      // Listen to form changes and compute input overrides from the mapping
      if (this.root && this.component?.overrideMapping && !this._changeListenerAttached) {
        this._changeListenerAttached = true;
        this._changeHandler = () => this._computeAndSetOverrides();
        this.root.on('change', this._changeHandler);
        // Compute the initial overrides immediately (no debounce) so a pre-filled
        // form paints its preview without the 1.5s delay.
        this._computeAndSetOverrides(true);
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
      this._changeListenerAttached = false;
      return super.detach();
    }

    private _computeAndSetOverrides(immediate = false) {
      if (this._debounceTimer) {
        clearTimeout(this._debounceTimer);
      }
      this._debounceTimer = setTimeout(
        () => {
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
          const overrides = computeInputOverrides(mapping, formData);
          // Push null when there's nothing usable yet so the component reverts to
          // its "complete the form" placeholder instead of keeping a stale preview.
          this.setValue(Object.keys(overrides).length > 0 ? overrides : null);
        },
        immediate ? 0 : 1500,
      );
    }
  }

  // Re-register with the extended class
  Formio.Components.setComponent(EPISTOLA_DOCUMENT_PREVIEW_OPTIONS.type, PreviewWithOverrides);
}
