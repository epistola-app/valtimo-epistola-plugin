# Next Steps

Outstanding work to complete the Epistola Valtimo Plugin API integration.

## 1. Epistola Client Library: Make `variantId` Optional

**Problem**: The Kotlin client library (`app.epistola.contract:client-spring3-restclient`) defines `variantId` as a **non-null** `String` in `GenerateDocumentRequest`. However, Epistola determines the variant automatically when none is provided.

**Current workaround**: `EpistolaServiceImpl` passes `""` (empty string) when `variantId` is null. This passes the Kotlin constructor but fails the mock server's OpenAPI validation (`minLength: 3`, pattern `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`).

**Fix options**:
1. **Update the Epistola OpenAPI spec** to make `variantId` nullable/optional, then regenerate the client library. This is the proper fix.
2. **Use reflection** to bypass the non-null check — fragile, not recommended.

**How to implement option 1**:
- In the `epistola-contract` repo, update `openapi.yaml`: change `variantId` from `required` to optional and make it nullable
- Regenerate the Kotlin client: run the OpenAPI generator
- Publish a new version of `client-spring3-restclient`
- Update `libs.versions.toml` in this repo to reference the new version
- Remove the `variantId != null ? variantId : ""` workaround in `EpistolaServiceImpl.java`

---

## 2. End-to-End Verification with Real (or Fixed Mock) API

Once `variantId` is optional in the client library:

1. Start the stack: `docker compose -f docker/docker-compose.yml up -d`
2. Start the backend: `./gradlew :test-app:backend:bootRun --args='--spring.profiles.active=dev'`
3. Start the frontend: `cd test-app/frontend && pnpm start`
4. Create a Vergunningsaanvraag case and verify:
   - Backend log shows `Submitting document generation request: tenantId=..., templateId=bevestigingsbrief-vergunning, variantId=null, format=PDF`
   - Prism mock returns 200 with a `requestId`
   - Process advances past the generate-document service task
   - Check-job-status and download-document tasks complete successfully

---

## 3. Integration Tests: Verify with Updated Client

The existing `EpistolaServiceImplTest` uses Testcontainers with the Prism mock server. After updating the client library:

- Verify tests pass: `./gradlew :backend:plugin:test`
- Add test case for `variantId = null` (auto-determined variant)
- Add test case for `variantId = "some-variant"` (explicit variant)

---

## 4. Frontend: Mark `variantId` as Optional

In the generate-document configuration component, `variantId` should be clearly marked as optional with a helper text explaining that Epistola auto-determines the variant when not specified.

**File**: `frontend/plugin/src/lib/components/generate-document-configuration/`

---

## 5. Callback Webhook Security

The callback endpoint (`POST /api/v1/plugin/epistola/callback/generation-complete`) is currently publicly accessible without authentication. Consider adding:

- HMAC signature validation (Epistola signs the payload with a shared secret)
- IP allowlisting if the Epistola service has fixed IPs
- Rate limiting to prevent abuse

---

## 6. Documentation

- Update `README.md` with installation and configuration instructions
- Add BPMN examples showing the recommended async pattern (generate → message catch → check status → download)
- Document the callback webhook setup for production environments
