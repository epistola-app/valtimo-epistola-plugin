# Iframe embedding + postMessage bridge

The demo frontend (`test-app/frontend`) can be embedded in an `<iframe>` on a
trusted host page and driven from it via `postMessage`. This is the plumbing an
external training facility needs to launch exercises inside an embedded Valtimo
— none of that content lives here, only the ability to be framed and the
message protocol below. See [ADR 0005](adr/0005-iframe-embedding-bridge.md) for
the rationale.

This mirrors [epistola-suite's own embedding feature](https://github.com/epistola-app/epistola-suite)
(its ADR 0015) closely enough that one host page can drive an embedded Suite and
an embedded Valtimo with shared code — see [Relationship to the Suite
bridge](#relationship-to-the-suite-bridge) for what differs and why.

## Status

- **Off by default, everywhere.** Nothing is framable and no bridge runs unless
  embedding is both explicitly enabled _and_ given at least one valid parent
  origin.
- **Test-app only.** All of it lives in `test-app/frontend/src/app/embedding/`.
  The published plugin library (`@epistola.app/valtimo-plugin`) is untouched, so
  nothing here reaches consumers of the plugin.
- **No backend change.** Unlike the Suite, this app authenticates with a bearer
  token rather than a session cookie, so there is no `SameSite` work to do — see
  [Authentication inside an iframe](#authentication-inside-an-iframe).
- **Signing in never blanks the frame.** Embedded logins use `prompt=none`, which
  answers with a redirect whether or not the session is alive, and the app asks
  the host to drive a top-level sign-in rather than navigating itself somewhere
  it cannot render. Implemented for authentik; see
  [Known limitations](#known-limitations) for what that does _not_ cover.

Reading order: [Configuration](#configuration) to deploy it,
[The message protocol](#the-message-protocol) and
[Driving it from a host page](#driving-it-from-a-host-page) to build the host,
[Authentication inside an iframe](#authentication-inside-an-iframe) before
choosing domains, and [Known limitations](#known-limitations) before promising
anything to anyone.

## Configuration

Two settings, which must both be present for anything to happen:

| Runtime key (`window['env']`)   | Env var                            | Helm value                                |
| ------------------------------- | ---------------------------------- | ----------------------------------------- |
| `embeddingEnabled`              | `EMBEDDING_ENABLED`                | `frontend.embedding.enabled`              |
| `embeddingAllowedParentOrigins` | `EMBEDDING_ALLOWED_PARENT_ORIGINS` | `frontend.embedding.allowedParentOrigins` |

Origins are full origins — scheme, host, optional port — separated by commas or
whitespace. No paths, no wildcards. A trailing slash is tolerated as a typo.

```yaml
# Helm
frontend:
  embedding:
    enabled: true
    allowedParentOrigins:
      - https://epistola.app
```

```bash
# docker run
-e EMBEDDING_ENABLED=true \
-e EMBEDDING_ALLOWED_PARENT_ORIGINS=https://epistola.app,http://localhost:4321
```

Turning it on changes exactly two things:

1. **CSP `frame-ancestors`** on the nginx location that serves `index.html`
   becomes the space-joined origin list instead of `'none'`.
2. **The Angular bridge starts** — a `message` listener and `Router`
   subscription. With embedding off it is never constructed, so the app contains
   no `postMessage` listener at all.

`X-Frame-Options` is deliberately _not_ also sent. `frame-ancestors` supersedes
it in every browser Valtimo supports and, unlike `X-Frame-Options`, can express
a list — sending both would mean maintaining a header that can only contradict
the authoritative one.

### Where each deployment picks the value up

There are three copies of the nginx config and five of `config.js`, because the
image, docker-compose, and Helm each render their own. All of them default to
`frame-ancestors 'none'`.

| Deployment           | nginx config                                                                        | `config.js`                                     |
| -------------------- | ----------------------------------------------------------------------------------- | ----------------------------------------------- |
| Image (`docker run`) | `test-app/frontend/conf/default.conf`, rewritten at start by `docker-entrypoint.sh` | `envsubst` over `src/assets/config.template.js` |
| docker-compose       | `docker/containers/default.conf` (edit by hand)                                     | `docker/containers/config.js`                   |
| Helm                 | `charts/valtimo-demo/templates/frontend-nginx-configmap.yaml`                       | same ConfigMap                                  |
| `ng serve` (dev)     | none — the dev server sends no CSP, so it is framable by anyone locally             | `src/assets/config.js`                          |

Only the image path is environment-driven. docker-compose and Helm both
override the container's command, so the entrypoint never runs there — Helm
templates the value from `values.yaml`, and the compose config is a static file
you edit if you want to embed locally.

The chart rejects a wildcard or a bare hostname at template time rather than
rendering a policy that would be wider than intended:

```
Error: frontend.embedding.allowedParentOrigins: "*" is not an origin —
wildcards are not supported, list each origin in full
```

### Failure modes are all closed

Each of these leaves `frame-ancestors 'none'` and an inert bridge, rather than
a partially-applied configuration:

- Either setting unset, empty, or left as an unsubstituted `${VAR}`.
- `enabled` true with an empty, malformed, or entirely invalid origin list (the
  browser console says so; so does the container log).
- An origin carrying a path, query, credentials, a non-`http(s)` scheme, or a
  wildcard — it is dropped, and dropping all of them disables the feature.
- The container entrypoint not running at all (a `command:` override): the
  `'none'` baked into the config file stands, and nginx still starts.

## The message protocol

Every message carries a `source` so each side can pick its own traffic out of
whatever else is on the page. The bridge never posts with `'*'` as the target
origin, and validates both `event.origin` (against the allowlist) and
`event.source === window.parent` before acting on anything inbound.

| Direction  | `type`          | Carries                     | When                                                |
| ---------- | --------------- | --------------------------- | --------------------------------------------------- |
| app → host | `ready`         | `protocolVersion`           | Once, as soon as the bridge starts                  |
| app → host | `user`          | `userId`, `username`        | When the signed-in identity is known, and on change |
| app → host | `navigated`     | `path`, `resource \| null`  | Every `NavigationEnd`, de-duplicated                |
| app → host | `auth-required` | `reason`                    | Silent authentication was refused                   |
| host → app | `navigate`      | `target` (a typed identity) | Any time after `ready`                              |
| host → app | `retry-auth`    | nothing                     | After the host completes a top-level sign-in        |

`protocolVersion` is `1`. It is bumped only on a **breaking** change; new message
types are additive, and both sides ignore types they do not recognise, so a host
written against an older app keeps working.

### The two flows worth knowing

A normal load:

```
app  → ready
app  → user        { userId, username }        host: is this who I expect?
host → navigate    { view: 'case-type', … }
app  → navigated   /cases/form-flow-demo
     … learner works, each navigation reported …
```

A session that expires mid-exercise — note that the app never navigates its own
frame, which is what stops it going blank:

```
app  → auth-required  { reason: 'login_required' }
     … app holds on its bootstrap screen; host shows "sign in to continue" …
host   (user clicks) opens a TOP-LEVEL popup → user signs in → popup closes
host → retry-auth
app    re-runs its silent flow → session picked up
app  → ready → user → navigated
```

### App → host: `ready`

```jsonc
{ "source": "epistola-valtimo", "type": "ready", "protocolVersion": 1 }
```

Sent once, as soon as the bridge starts. **Wait for this before sending
`navigate`.** Angular bootstraps long after the document loads, so unlike a
server-rendered page there is a real window during which the iframe exists, has
a URL, and is not yet listening; a `navigate` sent then is dropped in silence.

`protocolVersion` is bumped only on a breaking change to these shapes.

### App → host: `navigated`

```jsonc
{
  "source": "epistola-valtimo",
  "type": "navigated",
  "path": "/cases/form-flow-demo/document/3f6c2a1e-.../summary",
  "resource": {
    "view": "case",
    "caseDefinitionKey": "form-flow-demo",
    "documentId": "3f6c2a1e-...",
    "tab": "summary",
  },
}
```

Fired on every Angular `NavigationEnd` — the initial load, in-app link clicks,
browser back/forward, and navigations the host itself asked for. Consecutive
duplicates of the same URL are suppressed.

`resource` is the typed identity when the route is one the vocabulary covers,
and `null` otherwise (an access-control screen, a 404, anything not in the table
below). That is normal, not an error: `path` is always present.

### App → host: `user`

```jsonc
{
  "source": "epistola-valtimo",
  "type": "user",
  "userId": "sub-123",
  "username": "trainee@demo.local",
}
```

Sent when the signed-in identity becomes known, and again only if it actually
changes. Lets the host check that the frame is showing the person it expects,
rather than whoever this browser happened to be signed in as already — an easy
mismatch when the host hands out per-user exercises.

Deliberately minimal: an identifier and a username, never email, name, or roles.
`userId` is the OIDC `sub` and is the field to compare on; either field may be
`null` if the provider did not supply it. Resolved through Valtimo's
`UserProviderService`, so it works the same for Keycloak and authentik.

> **This identifies, it does not authenticate.** It is an ordinary `postMessage`
> from a page the host chose to frame, not a signed assertion. Use it to detect a
> mismatch and react — warn, reload, refuse to start the exercise — never as
> proof of identity, and never as the basis for an authorization decision. The
> real check belongs on your API, against the user's own token.

### App → host: `auth-required`

```jsonc
{ "source": "epistola-valtimo", "type": "auth-required", "reason": "login_required" }
```

The app needs a human to sign in and **has deliberately not navigated its own
frame** to say so. See
[interactive re-authentication](#interactive-re-authentication) below for why
that distinction is the whole point.

`reason` is the OIDC error the provider returned to a `prompt=none` attempt —
usually `login_required`, but `consent_required` and `interaction_required` are
possible and a provider may define its own, so treat it as an opaque string.

On receiving this the host should present its own sign-in affordance and open a
**top-level** login (a popup or its own re-auth), then send `retry-auth`.

### Host → app: `retry-auth`

```jsonc
{ "source": "epistola-host", "type": "retry-auth" }
```

Tells the app that a top-level sign-in has completed and it should try again. It
carries no payload — it is a nudge, not a credential channel; the app re-runs its
own silent flow and picks the session up from the provider.

### Host → app: `navigate`

```jsonc
{
  "source": "epistola-host",
  "type": "navigate",
  "target": { "view": "case-type", "caseDefinitionKey": "form-flow-demo" },
}
```

The host supplies a **typed identity, never a URL or a router path**. This is
deliberate and is the load-bearing security property of the bridge: a raw path
would let the host aim an authenticated user's session at any route in the app,
which is open-redirect shaped even though it never leaves the origin.

`resolveRouteCommands` (`embed-resource.ts`) is a fixed lookup over the closed
set below, with every identifier format-checked, so no input produces a path
outside those shapes. Navigation is then performed through Valtimo's own
`Router`, so `AuthGuardService` and each route's role guards run exactly as they
do for an in-app link click — there is no privileged "host navigation" path that
could drift away from those checks.

An unknown `view` or a malformed identifier is ignored silently.

### The `view` vocabulary

| `view`           | Extra fields                                       | Route                                             |
| ---------------- | -------------------------------------------------- | ------------------------------------------------- |
| `home`           | —                                                  | `/`                                               |
| `cases`          | —                                                  | `/cases`                                          |
| `case-type`      | `caseDefinitionKey`                                | `/cases/{key}`                                    |
| `case`           | `caseDefinitionKey`, `documentId`, `tab?`          | `/cases/{key}/document/{id}[/{tab}]`              |
| `task`           | `caseDefinitionKey`, `documentId`, `tab`, `taskId` | `/cases/{key}/document/{id}/{tab}/tasks/{taskId}` |
| `tasks`          | —                                                  | `/tasks`                                          |
| `processes`      | —                                                  | `/processes`                                      |
| `plugins`        | —                                                  | `/plugins`                                        |
| `process-links`  | —                                                  | `/process-links`                                  |
| `epistola-admin` | —                                                  | `/epistola`                                       |

`documentId` must be a UUID. Every other identifier must match
`[A-Za-z0-9][A-Za-z0-9_-]{0,63}` — wide enough for authored case-definition keys
(`form-flow-demo`) and the training facility's generated per-trainee keys
(`t` + 15 hex chars, see `TraineeKeys`), and narrow enough that no identifier can
contain `.`, `/`, `?` or `#` and so reshape the path it lands in.

`tab` is optional on `case` because which tabs a case definition has is
configuration the host has no way to know; omitting it lets the app open its
default tab. It is required on `task`, whose route is nested under a tab segment
and has no shorter form.

## Driving it from a host page

A complete host, handling every message the app sends:

```html
<iframe id="valtimo" src="https://valtimo.example/" title="Valtimo"></iframe>
<script>
  const APP_ORIGIN = "https://valtimo.example";
  const frame = document.getElementById("valtimo");
  const expectedUserId = "sub-123"; // whoever this exercise was handed to

  function send(message) {
    frame.contentWindow.postMessage({ source: "epistola-host", ...message }, APP_ORIGIN);
  }

  window.addEventListener("message", (event) => {
    // Both checks are required and neither implies the other: the origin check
    // keeps out untrusted senders, the source check keeps out other frames and
    // popups on this same page.
    if (event.origin !== APP_ORIGIN) return;
    if (event.source !== frame.contentWindow) return;

    const message = event.data;
    if (!message || message.source !== "epistola-valtimo") return;

    switch (message.type) {
      case "ready":
        // Only now is the app listening. A navigate sent before this is dropped
        // in silence, because Angular has not bootstrapped yet.
        send({
          type: "navigate",
          target: { view: "case-type", caseDefinitionKey: "form-flow-demo" },
        });
        break;

      case "user":
        // A consistency check, never an authorization decision — see the
        // warning under the `user` message above.
        if (message.userId !== expectedUserId) {
          showWrongUserWarning(message.username);
        }
        break;

      case "navigated":
        trackProgress(message.path, message.resource);
        break;

      case "auth-required":
        // The frame has deliberately NOT navigated itself, because only a
        // top-level document can render a login page. Opening a popup needs a
        // real user gesture, so this has to be a button rather than an
        // immediate window.open.
        showSignInButton(() => {
          const popup = window.open(SIGN_IN_URL, "signin", "width=520,height=680");
          const poll = setInterval(() => {
            if (!popup || popup.closed) {
              clearInterval(poll);
              send({ type: "retry-auth" });
            }
          }, 500);
        });
        break;
    }
  });
</script>
```

Polling for `popup.closed` is the least-effort signal and is fine for a training
facility; a sign-in page you control can `postMessage` back to `window.opener`
instead, which is both faster and tells you whether the user actually completed
the login or just closed the window.

The host must validate `event.origin` and `event.source` on its own side, as
above. The bridge can only vouch for what it sends — it cannot stop anything else
on the page from posting to the host.

## Authentication inside an iframe

**The app's own API calls are unaffected.** nginx proxies `/api` to the backend
from the same origin as the app, and Valtimo authenticates with a bearer token
rather than a session cookie. Nothing about being framed changes that — which is
why, unlike epistola-suite, this feature needed no `SameSite` work anywhere.

Signing in is the part that interacts with framing, and one rule governs all of
it:

> **An identity provider can redirect _through_ a frame, but it cannot render
> _in_ one.**

`X-Frame-Options` and `frame-ancestors` are enforced only on the document that
finally commits — never on a redirect along the way. Measured against authentik:
its `/application/o/authorize/` answers an authenticated request with a bare
`302` that itself carries `X-Frame-Options: DENY`, and the browser follows it
without complaint. The frame lands on the callback and the flow completes.

So the question is never "does the IdP allow framing". It is **"does the IdP
need to render anything"**:

| Situation                                                      | Result in a frame                                  |
| -------------------------------------------------------------- | -------------------------------------------------- |
| Live session, no consent/MFA prompt due                        | Silent `302`. Works.                               |
| No session, expired session, consent screen, or re-auth prompt | IdP renders → framing headers apply → blank frame. |

### Per provider

|                                   | Keycloak (local dev/demo)                          | authentik (production)                                                                                                                            |
| --------------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| Session cookie, cross-site frame  | Realm-dependent                                    | `SameSite=None; Secure` automatically over HTTPS — its custom `SessionMiddleware` uses `"None" if secure else "Lax"`, so nothing to configure     |
| Framing headers on rendered pages | `X-Frame-Options: SAMEORIGIN`, relaxable per realm | `X-Frame-Options: DENY`, no CSP, and **not configurable** ([goauthentik#25259](https://github.com/goauthentik/authentik/issues/25259) still open) |
| Silent-renew iframe               | `checkLoginIframe: false` already — leave it off   | None at all                                                                                                                                       |
| Token refresh                     | Token-endpoint fetch                               | Token-endpoint `fetch` with a refresh token, no cookies                                                                                           |

The authentik path (`src/environments/auth/authentik-config.ts`) is a hand-rolled
authorization-code + PKCE implementation rather than a library, and that turns
out to help: it has no silent-renew iframe, the single most common reason
embedded SPAs break, and its refresh path touches no cookie at all. Once the
frame holds tokens it stays authenticated regardless of third-party cookie
policy.

### What to do about it

1. **Deploy the host page, this app, and the IdP on one registrable domain**
   (`epistola.app`, `valtimo.epistola.app`, `auth.epistola.app`). The frame is
   then same-site, the IdP session cookie is delivered, and the common path is a
   silent `302`. This is the recommended production shape.
2. **Put the host page behind the same IdP**, so a session already exists before
   any page frames this app. For a training facility that is the natural shape
   anyway.
3. Do **not** relax the IdP's framing headers to let its login page render in the
   frame. It puts credential entry inside a frame the host controls, which is the
   clickjacking exposure `frame-ancestors` exists to prevent — and with authentik
   it is not possible regardless.

`SameSite=None` makes a cookie _eligible_ to be sent cross-site, not guaranteed:
Safari has blocked third-party cookies since 2020 and Firefox partitions them,
so a genuinely cross-site deployment will still fall into the render path in
those browsers. That is another reason to prefer same-site.

> **Testing this locally will not reproduce the problem.** Every `localhost`
> port is the _same site_ — ports do not affect site calculation — so a host
> page on `http://localhost:4321` framing the app on `http://localhost:8092`
> is cross-origin but same-site, and the cookies flow normally. A local
> success therefore says nothing about a genuinely cross-site deployment. Use
> two distinct hostnames to test that.

### Interactive re-authentication

Everything above concerns getting _into_ the frame. This is what happens when a
session that was valid **expires while the app is embedded**.

Left alone, the app would call `login()`, navigate its own frame to the provider,
and the provider would have to render a login page — leaving the user staring at
a blank rectangle mid-exercise. That is not fixed by deploying same-site:
same-site governs cookie _delivery_, while an expired session needs interactive
login however the domains are arranged.

Two changes avoid it:

**1. Embedded logins always ask for `prompt=none`.** It is the only request shape
guaranteed to answer with a redirect in _both_ directions — an authorization code
when the session is live, `?error=login_required` when it is not. Neither renders,
so framing headers never engage and the frame never goes blank. Measured against
authentik 2025.6 with an implicit-consent authorization flow:

| session | response                                                                                           |
| ------- | -------------------------------------------------------------------------------------------------- |
| live    | `302 → /auth/callback?code=…` — carries `X-Frame-Options: DENY`, and the browser follows it anyway |
| gone    | `302 → /auth/callback?error=login_required`                                                        |

**2. When silent auth is refused, the app stops rather than redirects.** It
raises `auth-required` for the host and holds. Concretely:

- `AuthentikOidcService.login()` is the single chokepoint — the initializer, the
  route guard and the bearer interceptor all call it, so one branch covers all
  three. Once a silent attempt has been refused it no longer navigates at all,
  which is also what stops the guard and interceptor from redirect-looping.
- `AuthentikUserService.init()` leaves its `APP_INITIALIZER` promise **pending**.
  Angular stays on its bootstrap screen; resolving would boot a session-less app
  that renders the Valtimo shell and then 401s on everything, which is a worse
  thing to show than "still loading".
- A spent refresh token is no longer fatal on its own: the provider session often
  outlives it, so the tokens are dropped and the normal login path runs, which
  embedded means a `prompt=none` attempt that usually succeeds in silence.

The host then owns the interaction — it has the top-level context, and only a
top-level document can render a login page:

```js
if (message.type === "auth-required") {
  // Must originate from a real click: popups need a user gesture.
  showSignInButton(() => {
    window.open(SIGN_IN_URL, "signin", "width=520,height=680");
    // when that window reports success:
    frame.contentWindow.postMessage({ source: "epistola-host", type: "retry-auth" }, APP_ORIGIN);
  });
}
```

The popup only has to **re-establish the provider session cookie**. It must not
run the code exchange: the PKCE verifier lives in the frame's `sessionStorage`,
which the popup does not share. Once the cookie is back, `retry-auth` makes the
frame re-run its own silent flow and pick the session up.

Un-framed deployments are untouched by all of this — `isEmbeddedSession()` is
false, and `login()` keeps its existing full-redirect behaviour.

## What is verified, and how

Nothing below is inferred from a specification — each was measured against the
built artifact or pinned by a test that was checked to fail when the behaviour is
removed.

| Behaviour                                                                     | How it was established                                                                          |
| ----------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Default is un-framable in every deployment                                    | Built image across all env states; Chromium refused the frame (`chrome-error://chromewebdata/`) |
| An allowlisted origin can frame it                                            | Same two-container run, host page on a second origin                                            |
| Entrypoint bypassed, empty list, or an injection attempt still yield `'none'` | Built image, four failure states, nginx still starts in each                                    |
| Chart renders all four states and rejects wildcards                           | `helm template` / `helm lint`                                                                   |
| `ready` reaches a cross-origin host with the correct target origin            | Real browser, host page on `:4321` framing the image on `:8092`                                 |
| `ready` always precedes `user`                                                | Karma spec; verified to fail when the order is swapped                                          |
| Host cannot navigate by raw URL or traverse out of a route shape              | Karma specs; verified to fail when the identifier check is loosened                             |
| Inbound origin **and** `event.source` are both enforced                       | Karma specs; each check removed separately, each fails tests                                    |
| The bridge never posts to `'*'`                                               | Karma specs; substituting `'*'` fails six of them                                               |
| `user` carries no email, name, or roles                                       | Karma spec; adding `email` fails three                                                          |
| `prompt=none` answers with a redirect in **both** session states              | authentik 2025.6, implicit-consent flow, traced in a real browser and with `curl`               |
| Framing headers are not enforced on a redirect                                | Same trace: the authorize `302` carries `X-Frame-Options: DENY` and Chromium follows it         |

Run the specs with `cd test-app/frontend && pnpm test` (Karma, real Chrome —
chosen over the plugin library's jsdom-based Jest precisely because this code
depends on real `postMessage` and `MessageEvent` semantics). They live beside
the source in `src/app/embedding/`.

## Troubleshooting

| Symptom                                                              | Most likely cause                                                                                                                      |
| -------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Frame is blank immediately, console says "Refused to display"        | `frame-ancestors` does not list the host origin. Check the served header, not the config — compose and Helm override the entrypoint    |
| Frame loads, but no messages ever arrive                             | `embeddingEnabled` is off or every origin was rejected. The browser console carries an explicit warning, and so does the container log |
| Messages arrive but `navigate` does nothing                          | Sent before `ready`, an unknown `view`, or an identifier that failed validation. All are ignored in silence by design                  |
| `ready` never arrives                                                | The app did not finish bootstrapping. Look for `auth-required` — it is held deliberately when silent auth was refused                  |
| `auth-required` immediately, every time                              | The provider session cookie is not reaching the provider from inside the frame. Cross-site plus Safari is the usual cause              |
| The CSP header is right in `conf/default.conf` but absent at runtime | Helm and compose each mount their own nginx config; the image's copy is not in play there                                              |
| Works locally, fails in production                                   | Every `localhost` port is the same _site_. A local success does not exercise cross-site cookie behaviour at all                        |

## Known limitations

### Authentication

- **Only the authentik integration does silent re-auth.** The Keycloak options in
  `keycloak-config.ts` still use `onLoad: 'login-required'`, which renders on an
  expired session and so blanks the frame. authentik is the production provider
  and Keycloak the local demo one, so this is deliberate rather than overlooked —
  but embedding the Keycloak-configured stack has the old behaviour. The
  equivalent there is `onLoad: 'check-sso'` with a `silentCheckSsoRedirectUri`.
- **Re-authentication loses in-progress work.** `retry-auth` re-runs the silent
  flow by navigating the frame, so a half-filled form is lost. Avoiding that
  needs the code exchange to happen in a hidden nested iframe, which in turn
  needs a dedicated static callback page registered as an extra redirect URI on
  the provider.
- **Cross-site embedding degrades in privacy-restricting browsers.** Safari has
  blocked third-party cookies since 2020 and Firefox partitions them, so the
  provider session cookie may not arrive and every load ends in `auth-required`.
  Functionally safe — no blank frame — but a sign-in per session is poor. Deploy
  same-site.
- **`sessionStorage` in a third-party frame on Safari is unverified.** Tokens and
  the PKCE verifier both live there. If a write silently fails, the flow breaks
  before cookies are even reached. Untested; worth checking before any
  genuinely cross-site rollout.
- **The `user` message identifies, it does not authenticate.** It is an ordinary
  `postMessage`, not a signed assertion. Never use it for authorization.

### Protocol

- **Nothing reports data changes.** The Suite's bridge also emits
  `resource-changed` on create/update/delete; there is no equivalent here, so a
  host learns that a learner opened a task, not that they completed it. Valtimo's
  API has no single URL convention to classify generically the way the Suite's
  does, so it needs its own design rather than a translation.
- **`navigate` is not acknowledged.** An unknown view, a malformed identifier, or
  a message sent before `ready` is ignored with no reply, so a host cannot tell
  "refused" from "not listening yet". Wait for `ready`, and treat a missing
  `navigated` as the signal.
- **A routing error is reported as a reload.** `AppRoutingModule` handles router
  errors with `window.location.href = '/'`, a full document load rather than a
  `NavigationEnd`. The host sees the frame reload and a fresh `ready`, not a
  `navigated` for the URL that failed.
- **No height or resize message.** The host sizes the frame itself.

### Deployment

- **`ng serve` sends no CSP**, so the dev server is framable by any origin
  regardless of these settings. Only the bridge half is configurable in dev.
- **The same values live in several files.** Three nginx configs and five
  `config.js` copies, because the image, compose, and Helm each render their own
  and only the image path is environment-driven. A new `window['env']` key added
  only to `config.template.js` does nothing in compose or Helm — `epistolaEnabled`
  was broken exactly that way until this change.

### Testing

- **No committed cross-origin E2E.** The browser checks in the table above were
  run by hand. The repo has no cross-origin fixture-server harness, and a full
  authenticated flow additionally needs the provider configured with the framing
  origin as a redirect URI.

## Relationship to the Suite bridge

Same shape, three deliberate differences:

- **Discriminators.** Inbound is `epistola-host` in both, so a host speaks one
  dialect. Outbound is `epistola-valtimo` here versus `epistola-suite` there, so
  a host driving both can tell them apart.
- **`ready` is new.** The Suite's bridge is a synchronous script in a
  server-rendered page and is listening before the host can plausibly post to
  it. Angular is not, so the handshake is necessary rather than merely tidy.
- **Identity shape.** The Suite addresses catalog resources
  (`{resourceType, tenantId, catalogKey, key}`); this addresses Valtimo routes
  (`{view, ...}`). The security property — a closed lookup, format-checked
  identifiers, navigation through the app's ordinary path — is identical.

Not shared: the Suite's cookie `SameSite` handling (not needed here, see above)
and its `resource-changed` message (not implemented here, see above).
