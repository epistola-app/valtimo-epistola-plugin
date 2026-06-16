/**
 * Hides a registered custom Formio component from the builder's component palette,
 * while keeping it fully usable inside other components' `editForm`s and at runtime.
 *
 * Formio's `WebformBuilder` only adds a component to the palette when
 * `component.builderInfo && component.builderInfo.schema` is truthy. Overriding the
 * registered class's static `builderInfo` getter to `false` therefore removes it from
 * the palette. Runtime instantiation and editForm usage don't consult `builderInfo`,
 * so they are unaffected.
 *
 * Call this AFTER the component is registered (and after any `setComponent` re-registration),
 * so it targets the final class in `Formio.Components.components[type]`.
 */
export function hideFormioComponentFromBuilder(type: string): void {
  const registered = (window as any).Formio?.Components?.components?.[type];
  if (registered) {
    Object.defineProperty(registered, 'builderInfo', {
      get: () => false,
      configurable: true,
    });
  }
}
