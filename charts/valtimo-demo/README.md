# Valtimo Demo Helm Chart

Deploys the full Valtimo + Epistola demo stack (Angular frontend, Spring Boot
backend, Epistola server, Keycloak, PostgreSQL/CNPG) with sensible defaults for
shared demo environments.

## Components

- **Valtimo backend** (`demo-backend` image) with Epistola plugin
- **Valtimo frontend** (`demo-frontend` image served via nginx)
- **Epistola server** (contract/API implementation)
- **Keycloak 26** with pre-configured realm, self-service registration, and
  WebAuthn-enabled authentication flows
- **PostgreSQL** via CloudNativePG (or external DB)

## Prerequisites

- Kubernetes 1.27+
- Helm 3.13+
- Storage class for CNPG (or configure `database.type` accordingly)
- Ingress controller / load balancer if exposing the demo publicly

## Quick Install

```bash
helm repo add epistola https://epistola-app.github.io/valtimo-epistola-plugin
helm dependency build charts/valtimo-demo

helm install demo charts/valtimo-demo \
  --set publicUrls.frontend="https://valtimo.demo.example" \
  --set publicUrls.keycloak="https://auth.demo.example/auth" \
  --set keycloak.webAuthn.rpId="valtimo.demo.example" \
  --set keycloak.webAuthn.passwordlessRpId="auth.demo.example"
```

> The RP IDs must match the effective domain (no scheme) served over TLS so
> browsers allow passkey enrollment.

After installation retrieve the generated Keycloak admin password (used only
for console access; normal users self-register):

```bash
kubectl get secret demo-valtimo-demo-backend \
  -o jsonpath='{.data.keycloak-admin-password}' | base64 -d
```

## Important Values

| Key | Description |
| --- | --- |
| `publicUrls.frontend` | Fully-qualified URL for the Angular app. Derives redirect URIs, whitelisted origins, and `VALTIMO_APP_HOSTNAME`. |
| `publicUrls.keycloak` | Fully-qualified Keycloak URL (include `/auth`). Used by frontend + backend and for `KC_HOSTNAME`. |
| `keycloak.webAuthn.*` | WebAuthn relying party settings (entity names, RP IDs, signature algorithms, user verification). Both password-based and passwordless flows use these values. |
| `secrets.existingSecret` | Name of a pre-existing Secret to use instead of the chart-managed one. See [Secrets Management](#secrets-management). |
| `database.type` | Choose between `cnpg` (default), `cnpgExisting`, or `external`. |
| `epistola.enabled` | Deploy bundled Epistola chart (`true`) or point to `externalEpistola.url`. |
| `ingress.*` | Configure ingress hosts/tls or rely on an external gateway. When ingress is disabled you must provide `publicUrls.*`. |

See `values.yaml` for the exhaustive list of tunables.

## Epistola + Keycloak Integration

Epistola authenticates through the same Keycloak realm as Valtimo. The chart
automatically adds an `epistola-suite` OAuth2 client to the realm when
`epistola.enabled` is true.

### How it works

In Kubernetes, Keycloak is reachable at different URLs from browsers vs.
backend pods:
- **Browser**: `https://auth.example.com/auth` (via ingress)
- **Pods**: `http://<release>-keycloak/auth` (in-cluster service)

The chart handles this split-horizon problem with:
1. **KC_HOSTNAME** set to the full external URL so JWT `iss` claims are
   consistent regardless of how Keycloak is accessed
2. **KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true** so Keycloak accepts
   server-to-server requests on the internal service URL
3. **epistola.keycloak.backchannelBaseUrl** so Epistola uses the internal
   URL for token exchange, JWK fetching, and userinfo calls

### Example: full deployment with Keycloak SSO

```bash
helm install demo charts/valtimo-demo \
  --set publicUrls.frontend="https://valtimo.example.com" \
  --set publicUrls.keycloak="https://auth.example.com/auth" \
  --set epistola.keycloak.enabled=true \
  --set epistola.keycloak.issuerUri="https://auth.example.com/auth/realms/valtimo" \
  --set epistola.keycloak.backchannelBaseUrl="http://demo-keycloak/auth" \
  --set epistola.keycloak.existingSecret="epistola-keycloak-secret" \
  --set epistola.config.profiles="demo,prod"
```

Create the Epistola client secret beforehand:
```bash
kubectl create secret generic epistola-keycloak-secret \
  --from-literal=client-secret="your-secret-here"
```

### External database mode

For environments without the CNPG operator, deploy PostgreSQL separately and use
`database.type: external`:

```bash
helm install demo charts/valtimo-demo \
  --set database.type=external \
  --set database.external.host=postgresql \
  --set database.external.existingSecret=postgresql \
  --set publicUrls.frontend="https://valtimo.example.com" \
  --set publicUrls.keycloak="https://auth.example.com/auth"
```

## Secrets Management

Each credential supports three modes: **auto-generated** (default), **explicit
value**, or **secretRef** (reference an existing K8s Secret). This allows sharing
secrets across apps — e.g., the same Keycloak client secret used by both
valtimo-demo and epistola.

### Auto-generated (default)

Leave `value` empty — the chart generates a random value and stores it in the
chart-managed Secret. Values persist across upgrades.

```yaml
secrets:
  keycloakClientSecret:
    value: ""              # auto-generated (40 chars)
  pluginEncryptionSecret:
    value: ""              # auto-generated (32 chars)
  operatonAdminPassword:
    value: ""              # auto-generated (24 chars)
```

### Explicit value

Set `value` to a known string:

```yaml
secrets:
  operatonAdminPassword:
    value: "admin"
```

### Per-credential secret reference (recommended for production)

Point each credential to its own K8s Secret. Enables sharing secrets across
charts without duplication:

```yaml
secrets:
  keycloakClientSecret:
    secretRef:
      name: keycloak-valtimo-backend    # Secret name
      key: client-secret                # key within that Secret
  operatonAdminPassword:
    secretRef:
      name: operaton-admin
      key: password
  epistolaClientSecret:
    secretRef:
      name: epistola-keycloak-client    # same Secret used by epistola chart
      key: client-secret
  # pluginEncryptionSecret left empty → auto-generated into chart Secret
```

When `secretRef.name` is set for a credential, it is read directly from that
Secret and excluded from the chart-managed Secret.

### Legacy: single existing secret

Set `secrets.existingSecret` to reference one Secret for all credentials:

```yaml
secrets:
  existingSecret: "my-valtimo-sealed-secret"
```

The Secret must contain keys: `keycloak-client-secret`, `plugin-encryption-secret`,
`operaton-admin-password`, `keycloak-admin-password` (when Keycloak enabled),
`epistola-client-secret` (when Epistola enabled).

Then reference it in your values:

```bash
helm install demo charts/valtimo-demo \
  --set secrets.existingSecret=my-valtimo-sealed-secret \
  --set publicUrls.frontend="https://valtimo.example.com" \
  --set publicUrls.keycloak="https://auth.example.com/auth"
```

> When `existingSecret` is set, the `checksum/secret` pod annotation is omitted since
> the chart does not manage the secret contents. If you need rolling restarts on secret
> changes, use a tool like [Reloader](https://github.com/stakater/Reloader).

## Authentication & Demo Flow

- Self-service registration is enabled; every new user is placed in the
  `valtimo-users` group (grants `ROLE_USER`).
- The default browser flow (`browser-with-passkeys`) offers both password and
  passkey login. Users can enroll a passkey from their Keycloak profile via the
  "WebAuthn Register" required action.
- A secure Keycloak admin password is generated on install (unless provided) to
  avoid shipping `admin/admin`. Reusing the existing secret keeps credentials
  stable across upgrades.

## Database Reset Endpoint

The backend exposes `POST /api/v1/test/reset` (requires authentication) to drop
and recreate the Valtimo schema, then exits so Kubernetes restarts pods with a
clean slate. Use this to reset multi-user demo clusters.

```bash
TOKEN=$(curl -s -X POST "https://auth.demo.example/auth/realms/valtimo/protocol/openid-connect/token" \
  -d 'grant_type=password&client_id=valtimo-console&username=<user>&password=<pass>' | jq -r '.access_token')

curl -X POST https://valtimo.demo.example/api/v1/test/reset \
  -H "Authorization: Bearer $TOKEN"
```

## Upgrades & Persistence

- Keycloak admin password and OAuth client secret live in the backend secret.
  Helm reuses the existing secret to keep credentials consistent.
- CNPG stores database state in the provisioned PVC; upgrading the chart keeps
  case data unless you explicitly reset the DB or delete PVCs.
- When toggling ingress/public URLs, ensure RP IDs and TLS certificates match
  the new hostnames so WebAuthn continues to function.

## Troubleshooting

- **Cannot register passkey**: verify `keycloak.webAuthn.rpId` (and passwordless
  equivalent) exactly match the browser-visible domain. For multi-domain setups
  configure separate RP IDs.
- **OIDC redirect mismatch**: set `publicUrls.frontend`/`publicUrls.keycloak`
  (or `keycloakRealm.redirectUris/webOrigins`) so the realm export contains the
  correct URIs.
- **Unknown Keycloak admin password**: use the command above to read it from the
  secret instead of recreating the chart.
