# ADR 0005 — Iframe embedding and the postMessage bridge

- **Status:** Accepted
- **Date:** 2026-09-10
- **Deciders:** Epistola plugin maintainers
- **Related:** `test-app/frontend/src/app/embedding/`, `docs/embedding.md`, `docs/training-facility.md`, epistola-suite ADR 0015

## Context and problem statement

An external training facility wants to launch exercises by embedding this
repo's demo Valtimo in an `<iframe>` and driving it from the host page —
"open the learner's own case type", "which screen are they on now". epistola-suite
already does exactly this (its ADR 0015); the same host page now needs to talk
to an embedded Valtimo as well.

Three things were needed: the ability to be framed at all but only by named
origins, host → app navigation by identity, and app → host notification on
every navigation.

Two facts about the starting point shaped most of what follows.

**This repo shipped no framing header at all.** A grep for `X-Frame-Options`,
`frame-ancestors`, or `Content-Security-Policy` across every nginx config, the
Helm chart, and both Spring security layers returned nothing. So the situation
was not "framing is blocked and must be opened up" — it was that every
deployment of the demo frontend was already embeddable by any site on the
internet, unintentionally. The Suite's equivalent change relaxed a deliberate
`frame-ancestors 'none'`; this one has to _introduce_ that default and then
relax it, which makes the fail-closed behaviour the load-bearing part rather
than a nicety.

**This app is an Angular SPA with bearer-token auth**, not a server-rendered
htmx app with a session cookie. That removes the Suite's blocking prerequisite
(cookies dying inside a cross-origin frame) and replaces it with a different
one (the app is not listening for a good while after the document loads).

## Decision

### It lives in the test-app, not the published library

`@epistola.app/valtimo-plugin` is untouched. The bridge is about _Valtimo's_
routes and navigation, not about Epistola document generation, and the only
thing anyone would actually frame is the demo app that this repo deploys. This
matches the precedent set by the [training facility](../training-facility.md),
which is likewise test-app-only.

The cost is that a consumer's own Valtimo cannot reuse this without copying
~250 lines. That was accepted over the alternative — permanent public API
surface on the published package for a feature no consumer has asked for — and
the code is kept self-contained in one folder so lifting it stays cheap.

A side effect worth noting: `test-app/frontend` tests under Karma in real
Chrome, which is a better place to assert `postMessage` and `MessageEvent`
semantics than the library's jsdom-based Jest setup would have been.

### Two settings, gating two independent mechanisms

`embeddingEnabled` and `embeddingAllowedParentOrigins` (as `window['env']` keys,
env vars, or Helm values) control the CSP `frame-ancestors` allowlist nginx
serves _and_ whether the Angular bridge starts. Neither is derived from the
other, and both default off.

Install-wide boot-time configuration rather than anything per-tenant or
per-user, for the same reason as the Suite: which origins may frame a
deployment is a property of the environment, not of who is logged in.

### Every layer fails closed on its own

The value baked into all three nginx configs is `frame-ancestors 'none'`, and
the container entrypoint only ever _rewrites_ it once a usable allowlist has
been established. So an unset variable, a typo, an empty list, a wildcard, a
value containing characters an origin cannot contain, or the entrypoint not
running at all each leave the app un-framable — and nginx still starts, rather
than the deployment breaking.

This is why the entrypoint rewrites a literal default in place rather than
generating an `include`d snippet: a missing include file either fails nginx
startup outright or, if made tolerant with a wildcard, silently yields _no_
header — and "no header" is precisely the pre-existing bug this change exists to
close. `/etc/nginx/conf.d` is writable by uid 1000 in `nginx-unprivileged`,
which makes the in-place rewrite viable.

The Helm chart additionally rejects a wildcard or bare hostname at template
time. CSP would happily accept `frame-ancestors *`, while the bridge could never
`postMessage` to such an "origin" — so the two halves would disagree, in the
insecure direction, from one plausible typo.

### `X-Frame-Options` is not sent alongside

`frame-ancestors` supersedes it in every browser Valtimo supports and, unlike
`X-Frame-Options`, expresses a list. Sending both means maintaining a second
header that can only ever contradict the authoritative one. The Suite reached
the same conclusion from the other direction, having to explicitly _disable_
Spring Security's default `DENY`.

### The CSP header is scoped to the `location` serving `index.html`

nginx drops an inherited `add_header` in any `location` that declares one of its
own, so a server-level directive would have been silently dropped by the two
locations that already set `Cache-Control`. Scoping it to `location /` is also
correct on the merits: the API proxy location must keep serving PDFs that the
app frames itself, and hashed JS/CSS are never framed documents.

### The host names a destination; it never supplies one

A `navigate` message carries `{ view, ... }` — a typed identity resolved through
a fixed lookup table with every identifier format-checked. It never carries a
URL or a router path.

Accepting a path would let the host aim an authenticated user's session at any
route in the app. That is open-redirect shaped even though it never leaves the
origin, and it is a capability the host should not have merely by virtue of
being allowed to frame the page. Identifiers are restricted to a character set
excluding `.`, `/`, `?` and `#`, so no input can climb out of the shape it was
placed in.

Navigation is then performed with Valtimo's own `Router`, so `AuthGuardService`
and each route's role guards run exactly as for an in-app link click. This was
chosen over any bespoke resolution step for the same reason the Suite routes
through `htmx.ajax`: the destination's existing guards already _are_ the
enforcement point, and a second path to the same screens is a second thing to
keep correct.

Note what this boundary is and is not. It is an _addressing_ boundary — it
bounds which screens the host can name. It is not the authorization boundary;
that remains Valtimo's guards and the backend's PBAC, unchanged.

### A `ready` handshake, which the Suite does not have

The Suite's bridge is a synchronous script in a server-rendered page: by the
time the host can plausibly post to it, it is listening. Angular is not — there
is a real window in which the iframe exists, has a URL, and has no listener, so
an early `navigate` would be dropped in silence with nothing to retry against.
`ready` closes that window, and carries a `protocolVersion` so later additions
can be made compatibly.

### Resource identity is derived client-side, from the URL

Both directions share one table. A `navigated` message's `resource` comes from
running the app's own URL back through the same matcher that builds URLs for
`navigate`, so there is nothing for the backend to contribute that the client
cannot derive — the same conclusion the Suite reached after deleting an earlier
server-side interceptor that did this.

Routes outside the vocabulary yield `resource: null` rather than an error;
`path` is always present, so the host is never left guessing.

### Before the target origin is known, `ready` addresses every allowed origin

With one configured origin there is nothing to resolve. With several, the
referrer usually names the real parent — but when it does not, posting to each
allowlisted origin in turn is what keeps `ready` reaching a host that has not
spoken yet. The browser delivers only to the one that genuinely is the parent
and drops the rest, and every candidate is an origin the operator already
trusted to frame the app. `'*'` is never used. The Suite leaves the target
unresolved in this case, which it can afford to because it has no handshake to
deliver.

## Consequences

- Every deployment of the demo frontend now sends `frame-ancestors 'none'` by
  default, where it previously sent no framing header at all. Anyone who was
  relying on framing it — accidentally or otherwise — must now be allowlisted.
- `docs/embedding.md` documents the protocol, the deployment matrix, and the
  authentication constraints below.
- Fixing the config plumbing surfaced that `epistolaEnabled` had never been
  rendered into the Helm or docker-compose copies of `config.js`, so
  `EPISTOLA_ENABLED=false` silently did nothing in either. Both now render it.

### What this does not solve: signing in while framed

The app's own API calls are unaffected by framing — same origin, bearer token,
no cookie — which is why no `SameSite` work was needed. Establishing the session
is the part that can fail: an unauthenticated iframe redirects itself to
Keycloak, and if the browser withholds Keycloak's third-party SSO cookies
(Safari always; Chrome in the phase-out) Keycloak tries to render its login page
inside the frame, which its own `frame-ancestors 'self'` forbids.

The recommended answer is deployment shape — host page, app, and Keycloak on one
registrable domain makes the frame same-site and the problem disappears.
Relaxing Keycloak's realm security headers so its login page can be framed was
considered and rejected: it puts credential entry inside a frame the host
controls, which is the clickjacking exposure `frame-ancestors` exists to
prevent. `checkLoginIframe` was already `false` in this app, which is the
correct setting when framed.

## Alternatives considered

- **Putting the bridge in the published library** — better reuse and Jest
  coverage, at the price of permanent public API surface on the plugin package
  for a Valtimo-wide concern. Rejected; see above.
- **Accepting a path or URL in `navigate`** — far simpler, and how most
  embedding bridges are written. Rejected: it hands the host a capability over
  the user's session that framing alone should not confer.
- **Generating an `include`d nginx snippet at container start** — the tidier
  shape, but its failure mode is either "nginx will not start" or "no header at
  all", and the latter is the bug being fixed.
- **A server-side endpoint resolving identity → path** — would only duplicate
  the checks the destination route already performs.
- **Emitting `resource-changed` for create/update/delete**, as the Suite does.
  Deferred: the ask was navigation, and Valtimo's API surface has no single URL
  convention to classify generically the way the Suite's does, so this needs its
  own design rather than a translation of the Suite's listener.
