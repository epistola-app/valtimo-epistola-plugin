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
| `backend.existingSecret` | Name of a pre-existing Secret to use instead of the chart-managed one. See [Secrets Management](#secrets-management). |
| `backend.keycloak.backendClientSecret` | Secret shared with the Valtimo backend service account. Stored in `demo-valtimo-demo-backend` secret. |
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

By default, the chart creates a Kubernetes `Secret` named `<release>-valtimo-demo-backend`
containing the credentials listed below. For production or GitOps workflows you typically
want to manage secrets externally (e.g., via [Sealed Secrets](https://sealed-secrets.netlify.app/)
or an ESO `SecretStore`).

### Using an existing secret

Set `backend.existingSecret` to the name of a pre-existing Secret. The chart will
skip creating its own Secret and all deployments will reference the provided one instead.

```yaml
backend:
  existingSecret: "my-valtimo-sealed-secret"
```

The existing Secret must contain **all** of the following keys:

| Key | Description |
| --- | --- |
| `keycloak-client-secret` | Valtimo backend service-account secret for Keycloak |
| `plugin-encryption-secret` | AES key used by Valtimo to encrypt plugin properties |
| `operaton-admin-password` | Operaton BPMN engine admin password |
| `keycloak-admin-password` | Keycloak bootstrap admin password (required when `keycloak.enabled`) |

#### Example: Sealed Secrets

Create a SealedSecret that decrypts into the expected Secret:

```yaml
apiVersion: bitnami.com/v1alpha1
kind: SealedSecret
metadata:
  name: my-valtimo-sealed-secret
spec:
  encryptedData:
    keycloak-client-secret: AgBy3i...
    plugin-encryption-secret: AgCx8j...
    operaton-admin-password: AgDz2k...
    keycloak-admin-password: AgEw1l...
```

Then reference it in your values:

```bash
helm install demo charts/valtimo-demo \
  --set backend.existingSecret=my-valtimo-sealed-secret \
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
