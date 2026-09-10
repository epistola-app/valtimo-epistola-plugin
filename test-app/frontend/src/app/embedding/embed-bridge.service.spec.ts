// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { TestBed } from '@angular/core/testing';
import { NavigationEnd, Router } from '@angular/router';
import { Subject } from 'rxjs';
import {
  EMBED_PROTOCOL_VERSION,
  EMBEDDING_WINDOW,
  EmbedBridgeService,
} from './embed-bridge.service';

const HOST_ORIGIN = 'https://epistola.app';
const OTHER_ORIGIN = 'https://other.test';
const DOCUMENT_ID = '3f6c2a1e-9b4d-4c8a-8e5f-1d2b3c4d5e6f';

/**
 * A stand-in for the framed `window`. Karma runs specs top-level, where
 * `window.parent === window`, so the framed code path is unreachable without
 * this — `parent` is a distinct object with its own `postMessage` spy.
 */
class FakeWindow {
  readonly parent = { postMessage: jasmine.createSpy('postMessage') };
  readonly document = { referrer: '' };
  readonly listeners = new Map<string, ((event: unknown) => void)[]>();

  constructor(public env: Record<string, unknown> | undefined) {}

  addEventListener(type: string, listener: (event: unknown) => void): void {
    const existing = this.listeners.get(type) ?? [];
    this.listeners.set(type, [...existing, listener]);
  }

  removeEventListener(type: string, listener: (event: unknown) => void): void {
    this.listeners.set(
      type,
      (this.listeners.get(type) ?? []).filter((l) => l !== listener),
    );
  }

  /** Delivers a message as the browser would, defaulting to a well-formed trusted one. */
  deliver(overrides: Record<string, unknown> = {}): void {
    const event = { origin: HOST_ORIGIN, source: this.parent, data: {}, ...overrides };
    for (const listener of this.listeners.get('message') ?? []) listener(event);
  }

  get posts(): { message: Record<string, unknown>; targetOrigin: string }[] {
    return this.parent.postMessage.calls
      .allArgs()
      .map(([message, targetOrigin]) => ({ message, targetOrigin }));
  }

  postsOfType(type: string) {
    return this.posts.filter((post) => post.message['type'] === type);
  }
}

/** Only the surface the bridge touches. */
class FakeRouter {
  readonly events = new Subject<unknown>();
  readonly navigate = jasmine.createSpy('navigate').and.resolveTo(true);
  url = '/';

  navigateTo(url: string): void {
    this.url = url;
    this.events.next(new NavigationEnd(1, url, url));
  }
}

describe('EmbedBridgeService', () => {
  let router: FakeRouter;

  const enabledEnv = (origins = HOST_ORIGIN) => ({
    embeddingEnabled: 'true',
    embeddingAllowedParentOrigins: origins,
  });

  function createService(win: FakeWindow): EmbedBridgeService {
    router = new FakeRouter();
    TestBed.configureTestingModule({
      providers: [
        EmbedBridgeService,
        { provide: Router, useValue: router },
        { provide: EMBEDDING_WINDOW, useValue: win },
      ],
    });
    return TestBed.inject(EmbedBridgeService);
  }

  function startedIn(win: FakeWindow): EmbedBridgeService {
    const service = createService(win);
    service.start();
    return service;
  }

  describe('when it must stay inert', () => {
    it('does nothing when embedding is not enabled', () => {
      const win = new FakeWindow({});
      startedIn(win);

      expect(win.posts).toEqual([]);
      expect(win.listeners.get('message') ?? []).toEqual([]);
    });

    it('does nothing when enabled but no valid origin is configured', () => {
      spyOn(console, 'warn');
      const win = new FakeWindow({
        embeddingEnabled: 'true',
        embeddingAllowedParentOrigins: 'nonsense',
      });
      startedIn(win);

      expect(win.posts).toEqual([]);
    });

    it('does nothing when the app is not framed', () => {
      const win = new FakeWindow(enabledEnv());
      // A top-level document: its own parent.
      Object.defineProperty(win, 'parent', { value: win, configurable: true });

      const service = createService(win);
      service.start();

      expect(win.listeners.get('message') ?? []).toEqual([]);
    });

    it('stays inert for navigations too, not just at startup', () => {
      const win = new FakeWindow({});
      startedIn(win);

      router.navigateTo('/cases');

      expect(win.posts).toEqual([]);
    });
  });

  describe('announcing itself', () => {
    it('posts ready to the configured origin, never to "*"', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      const ready = win.postsOfType('ready');
      expect(ready.length).toBe(1);
      expect(ready[0].message).toEqual({
        source: 'epistola-valtimo',
        type: 'ready',
        protocolVersion: EMBED_PROTOCOL_VERSION,
      });
      expect(ready[0].targetOrigin).toBe(HOST_ORIGIN);
      expect(win.posts.every((post) => post.targetOrigin !== '*')).toBe(true);
    });

    it('is idempotent, so a second start does not double-register', () => {
      const win = new FakeWindow(enabledEnv());
      const service = startedIn(win);

      service.start();
      router.navigateTo('/cases');

      expect(win.postsOfType('ready').length).toBe(1);
      expect(win.postsOfType('navigated').length).toBe(1);
    });
  });

  describe('reporting navigations', () => {
    it('posts navigated with the parsed resource identity', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      router.navigateTo(`/cases/form-flow-demo/document/${DOCUMENT_ID}/summary`);

      expect(win.postsOfType('navigated')[0].message).toEqual({
        source: 'epistola-valtimo',
        type: 'navigated',
        path: `/cases/form-flow-demo/document/${DOCUMENT_ID}/summary`,
        resource: {
          view: 'case',
          caseDefinitionKey: 'form-flow-demo',
          documentId: DOCUMENT_ID,
          tab: 'summary',
        },
      });
    });

    it('still reports a path it cannot name, with a null resource', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      router.navigateTo('/access-control/17/summary');

      const navigated = win.postsOfType('navigated')[0].message;
      expect(navigated['path']).toBe('/access-control/17/summary');
      expect(navigated['resource']).toBeNull();
    });

    it('does not repeat itself when the same URL is re-emitted', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      router.navigateTo('/cases');
      router.navigateTo('/cases');
      router.navigateTo('/tasks');

      expect(win.postsOfType('navigated').map((post) => post.message['path'])).toEqual([
        '/cases',
        '/tasks',
      ]);
    });

    it('stops reporting once destroyed', () => {
      const win = new FakeWindow(enabledEnv());
      const service = startedIn(win);

      service.ngOnDestroy();
      router.navigateTo('/cases');

      expect(win.postsOfType('navigated')).toEqual([]);
      expect(win.listeners.get('message')).toEqual([]);
    });
  });

  describe('accepting host instructions', () => {
    const navigateMessage = (target: unknown) => ({
      data: { source: 'epistola-host', type: 'navigate', target },
    });

    it('navigates through the Router, so Valtimo’s guards still run', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      win.deliver(navigateMessage({ view: 'case-type', caseDefinitionKey: 'form-flow-demo' }));

      expect(router.navigate).toHaveBeenCalledWith(['/cases', 'form-flow-demo']);
    });

    it('ignores a message from an origin that is not allowlisted', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      win.deliver({ ...navigateMessage({ view: 'cases' }), origin: OTHER_ORIGIN });

      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('ignores a message that did not come from the parent frame', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      // Right origin, wrong sender — e.g. another frame or an opened popup.
      win.deliver({ ...navigateMessage({ view: 'cases' }), source: { postMessage: () => {} } });

      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('ignores traffic that is not ours', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      win.deliver({
        data: { source: 'some-other-widget', type: 'navigate', target: { view: 'cases' } },
      });
      win.deliver({ data: { source: 'epistola-host', type: 'something-else' } });
      win.deliver({ data: 'a bare string' });
      win.deliver({ data: null });

      expect(router.navigate).not.toHaveBeenCalled();
    });

    it('ignores an instruction it cannot resolve rather than guessing', () => {
      const win = new FakeWindow(enabledEnv());
      startedIn(win);

      win.deliver(navigateMessage({ view: 'case-type', caseDefinitionKey: '../access-control' }));
      win.deliver(navigateMessage({ view: 'not-a-view' }));
      win.deliver(navigateMessage('/access-control'));
      win.deliver(navigateMessage(undefined));

      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('resolving which origin to talk to', () => {
    it('uses the referrer to pick between several allowed origins', () => {
      const win = new FakeWindow(enabledEnv(`${OTHER_ORIGIN}, ${HOST_ORIGIN}`));
      win.document.referrer = `${HOST_ORIGIN}/training/exercise-1`;
      startedIn(win);

      expect(win.postsOfType('ready').map((post) => post.targetOrigin)).toEqual([HOST_ORIGIN]);
    });

    it('addresses every allowed origin while the target is unresolved', () => {
      // Each is operator-allowlisted, and the browser delivers only to the real
      // parent — this is what keeps `ready` reaching a host that has not spoken yet.
      const win = new FakeWindow(enabledEnv(`${OTHER_ORIGIN}, ${HOST_ORIGIN}`));
      startedIn(win);

      expect(win.postsOfType('ready').map((post) => post.targetOrigin)).toEqual([
        OTHER_ORIGIN,
        HOST_ORIGIN,
      ]);
    });

    it('narrows to the single origin once the host has spoken', () => {
      const win = new FakeWindow(enabledEnv(`${OTHER_ORIGIN}, ${HOST_ORIGIN}`));
      startedIn(win);

      win.deliver({
        data: { source: 'epistola-host', type: 'navigate', target: { view: 'cases' } },
      });
      router.navigateTo('/cases');

      expect(win.postsOfType('navigated').map((post) => post.targetOrigin)).toEqual([HOST_ORIGIN]);
    });

    it('does not let an untrusted message settle the target origin', () => {
      const win = new FakeWindow(enabledEnv(`${OTHER_ORIGIN}, ${HOST_ORIGIN}`));
      startedIn(win);

      win.deliver({ origin: 'https://attacker.test', data: { source: 'epistola-host' } });
      router.navigateTo('/cases');

      expect(win.postsOfType('navigated').map((post) => post.targetOrigin)).toEqual([
        OTHER_ORIGIN,
        HOST_ORIGIN,
      ]);
    });

    it('ignores a referrer that is not on the allowlist', () => {
      const win = new FakeWindow(enabledEnv(`${OTHER_ORIGIN}, ${HOST_ORIGIN}`));
      win.document.referrer = 'https://attacker.test/page';
      startedIn(win);

      expect(win.postsOfType('ready').map((post) => post.targetOrigin)).toEqual([
        OTHER_ORIGIN,
        HOST_ORIGIN,
      ]);
    });
  });
});
