import { hideFormioComponentFromBuilder } from './formio-builder-utils';

describe('hideFormioComponentFromBuilder', () => {
  afterEach(() => {
    delete (globalThis as any).window;
  });

  it('overrides the registered component builderInfo getter to false', () => {
    class Cmp {
      static get builderInfo() {
        return { schema: { type: 'epistola-override-builder' }, group: 'basic' };
      }
    }
    (globalThis as any).window = { Formio: { Components: { components: { 'epistola-x': Cmp } } } };

    expect((Cmp as any).builderInfo).toBeTruthy();
    hideFormioComponentFromBuilder('epistola-x');
    expect((Cmp as any).builderInfo).toBe(false);
  });

  it('is a no-op when the component is not registered', () => {
    (globalThis as any).window = { Formio: { Components: { components: {} } } };
    expect(() => hideFormioComponentFromBuilder('missing')).not.toThrow();
  });

  it('is a no-op when Formio is not present', () => {
    (globalThis as any).window = {};
    expect(() => hideFormioComponentFromBuilder('epistola-x')).not.toThrow();
  });
});
