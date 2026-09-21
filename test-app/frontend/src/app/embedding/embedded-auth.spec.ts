// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import {
  isEmbeddedSession,
  notifyAuthRetryRequested,
  notifyInteractiveAuthRequired,
  onAuthRetryRequested,
  onInteractiveAuthRequired,
  resetEmbeddedAuthListeners,
} from './embedded-auth';

describe('embedded auth signals', () => {
  afterEach(() => resetEmbeddedAuthListeners());

  it('delivers the refusal reason to every listener', () => {
    const first: string[] = [];
    const second: string[] = [];
    onInteractiveAuthRequired((r) => first.push(r));
    onInteractiveAuthRequired((r) => second.push(r));

    notifyInteractiveAuthRequired('login_required');

    expect(first).toEqual(['login_required']);
    expect(second).toEqual(['login_required']);
  });

  it('passes through whatever error the provider used, not a fixed set', () => {
    const seen: string[] = [];
    onInteractiveAuthRequired((r) => seen.push(r));

    notifyInteractiveAuthRequired('consent_required');
    notifyInteractiveAuthRequired('some_provider_specific_error');

    expect(seen).toEqual(['consent_required', 'some_provider_specific_error']);
  });

  it('stops delivering once unsubscribed', () => {
    const seen: string[] = [];
    const off = onInteractiveAuthRequired((r) => seen.push(r));

    notifyInteractiveAuthRequired('login_required');
    off();
    notifyInteractiveAuthRequired('login_required');

    expect(seen.length).toBe(1);
  });

  it('survives a listener that unsubscribes itself while being notified', () => {
    // The auth integration does exactly this on retry.
    const seen: string[] = [];
    const off = onInteractiveAuthRequired((r) => {
      seen.push(r);
      off();
    });
    onInteractiveAuthRequired((r) => seen.push(`second:${r}`));

    expect(() => notifyInteractiveAuthRequired('login_required')).not.toThrow();
    expect(seen).toEqual(['login_required', 'second:login_required']);
  });

  it('carries the host’s retry signal the other way', () => {
    let retries = 0;
    onAuthRetryRequested(() => retries++);

    notifyAuthRetryRequested();
    notifyAuthRetryRequested();

    expect(retries).toBe(2);
  });
});

describe('isEmbeddedSession', () => {
  const framed = (env: Record<string, unknown>) => {
    const win: Record<string, unknown> = { env };
    win['parent'] = { postMessage: () => {} };
    return win;
  };

  it('is true only when embedding is configured and the page is framed', () => {
    expect(
      isEmbeddedSession(
        framed({ embeddingEnabled: 'true', embeddingAllowedParentOrigins: 'https://epistola.app' }),
      ),
    ).toBe(true);
  });

  it('is false when framed but embedding is not enabled', () => {
    // Protects the un-framed deployment: the auth integration must keep its
    // existing full-redirect behaviour unless embedding is genuinely on.
    expect(isEmbeddedSession(framed({}))).toBe(false);
  });

  it('is false when enabled but the page is top-level', () => {
    const win: Record<string, unknown> = {
      env: { embeddingEnabled: 'true', embeddingAllowedParentOrigins: 'https://epistola.app' },
    };
    win['parent'] = win;
    expect(isEmbeddedSession(win)).toBe(false);
  });

  it('is false for a window with nothing on it', () => {
    expect(isEmbeddedSession({})).toBe(false);
    expect(isEmbeddedSession(undefined)).toBe(false);
  });
});
