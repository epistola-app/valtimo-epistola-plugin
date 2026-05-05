import { isEpistolaEnabled } from './epistola-runtime-config';

describe('isEpistolaEnabled', () => {
  const originalWindow: unknown = Reflect.get(globalThis, 'window');

  afterEach(() => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: originalWindow,
    });
  });

  it('defaults to enabled when window is unavailable', () => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: undefined,
    });

    expect(isEpistolaEnabled()).toBe(true);
  });

  it('defaults to enabled when the runtime flag is missing', () => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: {},
    });

    expect(isEpistolaEnabled()).toBe(true);
  });

  it('disables only for boolean false or string false', () => {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: { env: { epistolaEnabled: false } },
    });
    expect(isEpistolaEnabled()).toBe(false);

    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: { env: { epistolaEnabled: 'false' } },
    });
    expect(isEpistolaEnabled()).toBe(false);
  });

  it('enables for all other configured values', () => {
    for (const epistolaEnabled of [true, 'true', '', 'FALSE']) {
      Object.defineProperty(globalThis, 'window', {
        configurable: true,
        value: { env: { epistolaEnabled } },
      });

      expect(isEpistolaEnabled()).toBe(true);
    }
  });
});
