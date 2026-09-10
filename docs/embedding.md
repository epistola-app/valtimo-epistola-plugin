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

```html
<iframe id="valtimo" src="https://valtimo.example/" title="Valtimo"></iframe>
<script>
  const VALTIMO_ORIGIN = "https://valtimo.example";
  const frame = document.getElementById("valtimo");

  window.addEventListener("message", (event) => {
    if (event.origin !== VALTIMO_ORIGIN) return;
    if (event.source !== frame.contentWindow) return;
    const message = event.data;
    if (!message || message.source !== "epistola-valtimo") return;

    if (message.type === "ready") {
      // Only now is the app listening.
      frame.contentWindow.postMessage(
        {
          source: "epistola-host",
          type: "navigate",
          target: { view: "case-type", caseDefinitionKey: "form-flow-demo" },
        },
        VALTIMO_ORIGIN,
      );
    }

    if (message.type === "navigated") {
      console.log("learner is on", message.path, message.resource);
    }
  });
</script>
```

The host must validate `event.origin` and `event.source` on its own side too —
the bridge can only vouch for what it sends, not for what else may post to the
host page.

## Authentication inside an iframe

**The app's own API calls are unaffected.** nginx proxies `/api` to the backend
from the same origin as the app, and Valtimo authenticates with a bearer token
held in memory rather than a session cookie. Nothing about being framed changes
that — which is why, unlike epistola-suite, this feature needed no `SameSite`
changes anywhere.

**Establishing the session is the part that can break.** The app is configured
with Keycloak `onLoad: 'login-required'`, so an unauthenticated iframe redirects
itself to Keycloak, and:

- If the browser still sends Keycloak's SSO cookies, Keycloak redirects straight
  back and the user never sees a login page. Everything works.
- If it does not — Safari blocks third-party cookies outright, and Chrome does
  for users in the phase-out — Keycloak tries to _render_ its login page inside
  the frame, which its own default `frame-ancestors 'self'` forbids. The result
  is a blank frame.

In preference order:

1. **Deploy the host page, this app, and Keycloak on one registrable domain**
   (`training.example.org`, `valtimo.example.org`, `auth.example.org`). The
   iframe is then same-site, the cookies are first-party, and none of the above
   applies. This is the recommended production shape.
2. **Have the user sign in top-level first**, then embed — the host opens the app
   in a tab once and the established session carries into the frame.
3. Relaxing the Keycloak realm's `browserSecurityHeaders` so its login page can
   be framed. **Not done here and not recommended**: it means users type
   credentials inside a frame the host controls, which is exactly the
   clickjacking exposure `frame-ancestors` exists to prevent.

`checkLoginIframe` is already `false` in this app's Keycloak options
(`src/environments/auth/keycloak-config.ts`), which is the right setting when
framed — Keycloak's session-check iframe depends on third-party cookies and can
otherwise sign an embedded user out spuriously. Leave it off.

## Known gaps

- **A routing error is reported as a reload, not a navigation.**
  `AppRoutingModule` handles a router error with `window.location.href = '/'`, a
  full document load rather than a `NavigationEnd`. The host sees the frame
  reload and then a fresh `ready` + `navigated` for `/`, rather than a
  `navigated` for the URL that failed.
- **Nothing reports data changes.** The Suite's bridge also emits
  `resource-changed` on create/update/delete. There is no equivalent here yet: a
  host learns that a learner opened a task, not that they completed it. Adding
  it would mean an `HttpInterceptor` classifying Valtimo's API calls, along the
  lines of the Suite's `htmx:afterRequest` listener. The `ready` message's
  `protocolVersion` exists so that can be added compatibly.
- **`ng serve` sends no CSP**, so the dev server is framable by any origin
  regardless of these settings. Only the bridge half of the feature is
  configurable in dev.
- **Not covered by browser E2E.** The Karma specs cover the bridge thoroughly
  (including origin spoofing, path-traversal-shaped identifiers, and the
  fail-closed paths), and the container's header behaviour was verified against
  the built image, but there is no Playwright test that frames the app from a
  second origin — the repo has no cross-origin fixture-server harness.

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
