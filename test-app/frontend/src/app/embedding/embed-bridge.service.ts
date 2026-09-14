// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { Inject, Injectable, InjectionToken, Injector, OnDestroy } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { UserProviderService } from '@valtimo/security';
import { Subscription, filter } from 'rxjs';
import { EmbedResource, parseResourceFromUrl, resolveRouteCommands } from './embed-resource';
import { readEmbeddingConfig } from './embedding-config';
import {
  InteractiveAuthReason,
  notifyAuthRetryRequested,
  onInteractiveAuthRequired,
} from './embedded-auth';

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
    }
  | { source: typeof APP_SOURCE; type: 'auth-required'; reason: InteractiveAuthReason }
  | { source: typeof APP_SOURCE; type: 'user'; userId: string | null; username: string | null };

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
  private unsubscribeAuthRequired: (() => void) | null = null;
  private userSubscription: Subscription | null = null;
  private lastNotifiedUserId: string | null = null;
  // Separate from the value above on purpose: `null` is a legitimate user id
  // (an identity with no `sub`), so it cannot double as "nothing sent yet".
  private hasReportedUser = false;

  constructor(
    private readonly router: Router,
    private readonly injector: Injector,
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

    // The app raises this instead of sending its own frame to a login page it
    // cannot render. Relaying it is the whole point: only the host is in a
    // position to open a top-level sign-in.
    this.unsubscribeAuthRequired = onInteractiveAuthRequired((reason) =>
      this.post({ source: APP_SOURCE, type: 'auth-required', reason }),
    );

    // Angular boots long after the document does, so — unlike the server-rendered
    // Suite bridge — the host genuinely cannot tell when this app is listening.
    // Without `ready`, a host that posts `navigate` too early is ignored in
    // silence, with nothing to retry against.
    this.post({ source: APP_SOURCE, type: 'ready', protocolVersion: EMBED_PROTOCOL_VERSION });

    // Strictly after `ready`: the user identity is replayed from a ReplaySubject
    // and so can arrive synchronously, and a host that only starts handling
    // messages once it sees `ready` would otherwise miss it entirely.
    this.reportUserIdentity();
  }

  ngOnDestroy(): void {
    if (this.messageListener) {
      this.runtimeWindow.removeEventListener('message', this.messageListener);
      this.messageListener = null;
    }
    this.routerSubscription?.unsubscribe();
    this.routerSubscription = null;
    this.unsubscribeAuthRequired?.();
    this.unsubscribeAuthRequired = null;
    this.userSubscription?.unsubscribe();
    this.userSubscription = null;
  }

  /**
   * Tells the host who is signed in, so it can check the frame is showing the
   * person it expects rather than whoever this browser happens to be logged in
   * as — an easy mismatch when the host hands out per-user exercises.
   *
   * Resolved through Valtimo's `UserProviderService` rather than a specific
   * auth integration, so it works for Keycloak and authentik alike. Looked up
   * lazily and optionally: this runs from an environment initializer, and the
   * bridge must not fail to start because an auth provider is not wired.
   *
   * Deliberately only an identifier and a username — not email, name, or roles.
   * The host asked "is this the right person", which needs nothing more.
   */
  private reportUserIdentity(): void {
    let userProvider: UserProviderService | null = null;
    try {
      userProvider = this.injector.get(UserProviderService, null);
    } catch {
      return;
    }
    if (!userProvider) return;

    this.userSubscription = userProvider.getUserSubject().subscribe((identity) => {
      const userId = identity?.id ?? null;
      const username = identity?.username ?? null;
      if (this.hasReportedUser && userId === this.lastNotifiedUserId) return;
      this.hasReportedUser = true;
      this.lastNotifiedUserId = userId;
      this.post({ source: APP_SOURCE, type: 'user', userId, username });
    });
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
    if (message['source'] !== HOST_SOURCE) return;

    // The host has completed a top-level sign-in; the app can retry silently.
    // Carries no payload on purpose — it is a nudge, not a credential channel.
    if (message['type'] === 'retry-auth') {
      notifyAuthRetryRequested();
      return;
    }

    if (message['type'] !== 'navigate') return;

    // The host names a destination; it never supplies one. An unknown view or a
    // malformed identifier is ignored rather than guessed at.
    const commands = resolveRouteCommands(message['target']);
    if (commands === null) return;

    // Valtimo's own Router, so `AuthGuardService` and the route's role guards
    // run exactly as they would for an in-app link click.
    void this.router.navigate(commands);
  }
}
