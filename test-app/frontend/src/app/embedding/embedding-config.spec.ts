// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { parseAllowedParentOrigins, readEmbeddingConfig } from './embedding-config';

describe('readEmbeddingConfig', () => {
  const windowWith = (env: Record<string, unknown>) => ({ env });

  it('is disabled when there is no runtime env at all', () => {
    expect(readEmbeddingConfig({}).enabled).toBe(false);
    expect(readEmbeddingConfig(undefined).enabled).toBe(false);
  });

  it('defaults to disabled, unlike epistolaEnabled which defaults to on', () => {
    expect(readEmbeddingConfig(windowWith({})).enabled).toBe(false);
  });

  it('accepts the string flag that envsubst and the Helm config.js produce', () => {
    const config = readEmbeddingConfig(
      windowWith({
        embeddingEnabled: 'true',
        embeddingAllowedParentOrigins: 'https://epistola.app',
      }),
    );

    expect(config.enabled).toBe(true);
    expect(config.allowedParentOrigins).toEqual(['https://epistola.app']);
  });

  it('treats an unsubstituted or falsy flag as disabled', () => {
    for (const embeddingEnabled of ['', 'false', '${EMBEDDING_ENABLED}', false, undefined]) {
      const config = readEmbeddingConfig(
        windowWith({ embeddingEnabled, embeddingAllowedParentOrigins: 'https://epistola.app' }),
      );
      expect(config.enabled).withContext(String(embeddingEnabled)).toBe(false);
    }
  });

  it('fails closed when enabled but no origin survives validation', () => {
    const warn = spyOn(console, 'warn');

    const config = readEmbeddingConfig(
      windowWith({ embeddingEnabled: 'true', embeddingAllowedParentOrigins: 'not-an-origin' }),
    );

    expect(config.enabled).toBe(false);
    expect(config.allowedParentOrigins).toEqual([]);
    expect(warn).toHaveBeenCalled();
  });

  it('reports no origins rather than an empty-but-enabled state', () => {
    spyOn(console, 'warn');
    const config = readEmbeddingConfig(
      windowWith({ embeddingEnabled: 'true', embeddingAllowedParentOrigins: '' }),
    );

    // Callers rely on `enabled` alone implying "there is somewhere to post to".
    expect(config.enabled).toBe(false);
  });
});

describe('parseAllowedParentOrigins', () => {
  it('splits on commas, whitespace, or both', () => {
    expect(parseAllowedParentOrigins('https://a.test,https://b.test')).toEqual([
      'https://a.test',
      'https://b.test',
    ]);
    expect(parseAllowedParentOrigins('https://a.test https://b.test')).toEqual([
      'https://a.test',
      'https://b.test',
    ]);
    expect(parseAllowedParentOrigins(' https://a.test , https://b.test ')).toEqual([
      'https://a.test',
      'https://b.test',
    ]);
  });

  it('keeps an explicit port, which is a different origin', () => {
    expect(parseAllowedParentOrigins('http://localhost:4321')).toEqual(['http://localhost:4321']);
  });

  it('tolerates a trailing slash as an obvious typo', () => {
    expect(parseAllowedParentOrigins('https://epistola.app/')).toEqual(['https://epistola.app']);
  });

  it('rejects anything carrying more than an origin rather than truncating it', () => {
    // Silently widening "https://host/only/this/path" to the whole host would
    // grant more than the operator wrote.
    expect(parseAllowedParentOrigins('https://epistola.app/training')).toEqual([]);
    expect(parseAllowedParentOrigins('https://epistola.app?x=1')).toEqual([]);
    expect(parseAllowedParentOrigins('https://user:pw@epistola.app')).toEqual([]);
  });

  it('rejects non-http(s) schemes', () => {
    expect(parseAllowedParentOrigins('javascript:alert(1)')).toEqual([]);
    expect(parseAllowedParentOrigins('file:///etc/passwd')).toEqual([]);
    expect(parseAllowedParentOrigins('data:text/html,x')).toEqual([]);
  });

  it('rejects wildcards outright — CSP would accept them, postMessage cannot', () => {
    expect(parseAllowedParentOrigins('*')).toEqual([]);
    expect(parseAllowedParentOrigins('https://*.epistola.app')).toEqual([]);
  });

  it('drops invalid entries without discarding the valid ones beside them', () => {
    expect(
      parseAllowedParentOrigins('https://good.test, nonsense, https://also-good.test'),
    ).toEqual(['https://good.test', 'https://also-good.test']);
  });

  it('de-duplicates', () => {
    expect(parseAllowedParentOrigins('https://a.test, https://a.test/')).toEqual([
      'https://a.test',
    ]);
  });

  it('returns nothing for a non-string value', () => {
    expect(parseAllowedParentOrigins(undefined)).toEqual([]);
    expect(parseAllowedParentOrigins(['https://a.test'])).toEqual([]);
  });
});
