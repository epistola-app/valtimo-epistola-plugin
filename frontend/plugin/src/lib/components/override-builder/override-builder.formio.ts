import { Injector } from '@angular/core';
import { FormioCustomComponentInfo, registerCustomFormioComponent } from '@valtimo/components';
import { EpistolaOverrideBuilderComponent } from './override-builder.component';
import { hideFormioComponentFromBuilder } from '../formio-builder-utils';

export const EPISTOLA_OVERRIDE_BUILDER_OPTIONS: FormioCustomComponentInfo = {
  type: 'epistola-override-builder',
  selector: 'epistola-override-builder-element',
  title: 'Epistola Override Builder',
  group: 'basic',
  icon: 'list',
  emptyValue: null,
  fieldOptions: ['label', 'availableFields'],
};

/**
 * Recursively collect input field keys and labels from a Formio component tree.
 * Skips epistola custom components (which are builder UI, not form fields).
 */
function collectFormFields(components: any[]): { key: string; label: string }[] {
  const fields: { key: string; label: string }[] = [];
  for (const comp of components) {
    if (comp.input && comp.key && comp.type !== 'button' && !comp.type?.startsWith('epistola-')) {
      fields.push({ key: comp.key, label: comp.label || comp.key });
    }
    if (comp.components) {
      fields.push(...collectFormFields(comp.components));
    }
    if (comp.columns) {
      for (const col of comp.columns) {
        if (col.components) {
          fields.push(...collectFormFields(col.components));
        }
      }
    }
  }
  return fields;
}

export function registerEpistolaOverrideBuilderComponent(injector: Injector): void {
  if (customElements.get(EPISTOLA_OVERRIDE_BUILDER_OPTIONS.selector)) {
    return;
  }

  // Register the base component (Angular element + Formio component class)
  registerCustomFormioComponent(
    EPISTOLA_OVERRIDE_BUILDER_OPTIONS,
    EpistolaOverrideBuilderComponent,
    injector,
  );

  // Get the Formio Components registry and the registered base class
  const Formio = (window as any).Formio;
  if (!Formio?.Components) return;

  const BaseComponent = Formio.Components.components[EPISTOLA_OVERRIDE_BUILDER_OPTIONS.type];
  if (!BaseComponent) return;

  // Extend the base class to pass available form fields to the Angular component
  class OverrideBuilderWithFields extends BaseComponent {
    attach(element: HTMLElement) {
      // Set form fields on the component BEFORE super.attach() reads fieldOptions
      this.component.availableFields = this._extractFormFields();
      return super.attach(element);
    }

    private _extractFormFields(): { key: string; label: string }[] {
      // The Formio builder passes the main form schema as options.editForm
      // when opening the edit dialog (editFormOptions.editForm = this.form).
      const components = this.options?.editForm?.components;
      if (Array.isArray(components)) {
        return collectFormFields(components);
      }
      return [];
    }
  }

  // Re-register with the extended class
  Formio.Components.setComponent(EPISTOLA_OVERRIDE_BUILDER_OPTIONS.type, OverrideBuilderWithFields);

  // Internal editForm widget — not a standalone form field. Hide it from the builder palette.
  hideFormioComponentFromBuilder(EPISTOLA_OVERRIDE_BUILDER_OPTIONS.type);
}
