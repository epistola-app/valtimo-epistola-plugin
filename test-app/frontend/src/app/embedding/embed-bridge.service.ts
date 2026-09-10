// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { Inject, Injectable, InjectionToken, OnDestroy } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { EmbedResource, parseResourceFromUrl, resolveRouteCommands } from './embed-resource';
import { readEmbeddingConfig } from './embedding-config';

/**
 * `postMessage` bridge between this app and the host page framing it.
 * Protocol reference: `docs/embedding.md`; rationale: ADR 0005.
 *
 * The `source` discriminators are shared with epistola-suite's bridge on
 * purpose — a host driving both an embedded Suite and an embedded Valtimo
 * speaks one inbound dialect (`epistola-host`) and tells the two apart by the
 * outbound one (`epistola-suite` vs `epistola-valtimo`).
 */
const APP_SOURCE = 'epistola-valtimo';
const HOST_SOURCE = 'epistola-host';

/** Bumped only on a breaking protocol change; announced in the `ready` message. */
export const EMBED_PROTOCOL_VERSION = 1;

/** App → host. */
export type EmbedOutboundMessage =
  | { source: typeof APP_SOURCE; type: 'ready'; protocolVersion: number }
  | {
      source: typeof APP_SOURCE;
      type: 'navigated';
      path: string;
      resource: EmbedResource | null;
    };

/**
 * Indirection purely for testability: Karma runs specs top-level, where
 * `window.parent === window`, so a spec cannot otherwise exercise the framed
 * code path at all.
 */
export const EMBEDDING_WINDOW = new InjectionToken<Window>('EMBEDDING_WINDOW', {
  providedIn: 'root',
  factory: () => window,
});

@Injectable()
export class EmbedBridgeService implements OnDestroy {
  private started = false;
  private allowedOrigins: readonly string[] = [];
  private targetOrigin: string | null = null;
  private lastNotifiedPath: string | null = null;
  private messageListener: ((event: MessageEvent) => void) | null = null;
  private routerSubscription: Subscription | null = null;

  constructor(
    private readonly router: Router,
    @Inject(EMBEDDING_WINDOW) private readonly runtimeWindow: Window,
  ) {}

  /**
   * Idempotent, mirroring `EpistolaMenuService`'s latch: it is driven from an
   * environment initializer, and a double-registered `message` listener would
   * navigate twice per host instruction.
   */
  start(): void {
    if (this.started) return;

    const config = readEmbeddingConfig(this.runtimeWindow);
    if (!config.enabled) return;

    // Not framed: no host to talk to, and `window.parent === window` would make
    // every `event.source === parent` check trivially pass for messages this
    // very page sent to itself.
    if (this.runtimeWindow.parent === this.runtimeWindow) return;

    this.started = true;
    this.allowedOrigins = config.allowedParentOrigins;
    this.targetOrigin = this.resolveInitialTargetOrigin();

    this.messageListener = (event: MessageEvent) => this.onHostMessage(event);
    this.runtimeWindow.addEventListener('message', this.messageListener);

    this.routerSubscription = this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => this.notifyNavigated(event.urlAfterRedirects));

    // Angular boots long after the document does, so — unlike the server-rendered
    // Suite bridge — the host genuinely cannot tell when this app is listening.
    // Without `ready`, a host that posts `navigate` too early is ignored in
    // silence, with nothing to retry against.
    this.post({ source: APP_SOURCE, type: 'ready', protocolVersion: EMBED_PROTOCOL_VERSION });
  }

  ngOnDestroy(): void {
    if (this.messageListener) {
      this.runtimeWindow.removeEventListener('message', this.messageListener);
      this.messageListener = null;
    }
    this.routerSubscription?.unsubscribe();
    this.routerSubscription = null;
  }

  /**
   * With exactly one allowed origin there is nothing to resolve. With several,
   * the referrer names which one actually framed us; if it is unusable the
   * target stays unresolved and {@link post} falls back to addressing all of
   * them until the host's first message settles it.
   */
  private resolveInitialTargetOrigin(): string | null {
    if (this.allowedOrigins.length === 1) return this.allowedOrigins[0];

    const referrer = this.runtimeWindow.document?.referrer;
    if (!referrer) return null;
    try {
      const referrerOrigin = new URL(referrer).origin;
      return this.allowedOrigins.includes(referrerOrigin) ? referrerOrigin : null;
    } catch {
      return null;
    }
  }

  /**
   * Never posts with `'*'`. Before the target is pinned down, the message goes
   * to each allowlisted origin in turn: the browser delivers it only to the one
   * that is genuinely the parent and silently drops the rest, and every one of
   * them is an origin the operator already trusted to frame this app.
   */
  private post(message: EmbedOutboundMessage): void {
    const targets = this.targetOrigin !== null ? [this.targetOrigin] : this.allowedOrigins;
    for (const origin of targets) {
      this.runtimeWindow.parent.postMessage(message, origin);
    }
  }

  private notifyNavigated(url: string): void {
    if (url === this.lastNotifiedPath) return;
    this.lastNotifiedPath = url;
    this.post({
      source: APP_SOURCE,
      type: 'navigated',
      path: url,
      resource: parseResourceFromUrl(url),
    });
  }

  private onHostMessage(event: MessageEvent): void {
    // Both checks matter and neither implies the other: the origin check keeps
    // out untrusted senders, the source check keeps out same-origin frames and
    // popups that are not our host.
    if (event.source !== this.runtimeWindow.parent) return;
    if (!this.allowedOrigins.includes(event.origin)) return;

    // Only ever narrows an unresolved target to a verified origin.
    if (this.targetOrigin === null) this.targetOrigin = event.origin;

    const data: unknown = event.data;
    if (typeof data !== 'object' || data === null) return;

    const message = data as Record<string, unknown>;
    if (message['source'] !== HOST_SOURCE || message['type'] !== 'navigate') return;

    // The host names a destination; it never supplies one. An unknown view or a
    // malformed identifier is ignored rather than guessed at.
    const commands = resolveRouteCommands(message['target']);
    if (commands === null) return;

    // Valtimo's own Router, so `AuthGuardService` and the route's role guards
    // run exactly as they would for an in-app link click.
    void this.router.navigate(commands);
  }
}
