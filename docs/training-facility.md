# Interactive training facility

An opt-in sandbox in the test-app that lets people learn the plugin hands-on — fill in the demo
flow, configure their own process-link/plugin-config wiring — against one shared, always-on
instance, without being able to see or touch anyone else's work.

**Audience:** contributors extending or operating this repo's `test-app`. This is **not** part of
the published plugin artifact (`epistola-plugin` on Maven Central) — nothing here ships to
consumers of the plugin; it lives entirely under
`test-app/backend/src/main/kotlin/com/ritense/valtimo/epistola/training/`.

## Soft tenancy, not real tenancy

There is still one Valtimo instance, one database, one Epistola connection at the infrastructure
level. Isolation is achieved purely by **cloning a personal set of resources per trainee and
scoping authorization down to "your own clone"** — there is no DB-per-tenant or
schema-per-tenant partitioning, and nothing here talks to Epistola's own (genuinely multi-tenant)
tenant model except to provision one Epistola tenant per trainee as a side effect.

This matters for what the isolation actually guarantees: it is exactly as strong as the
authorization layer below, not as strong as infrastructure separation would be. A bug in that
layer is a cross-trainee data leak, not a contained blast radius — see
[The critical finding](#the-critical-finding-unconditioned-role_admin-pbac) for a real example.

## Enabling it

Activate the `training` Spring profile:

```bash
./gradlew :test-app:backend:bootRun --args='--spring.profiles.active=dev,training'
```

Off by default. `TrainingConfiguration` carries `@Profile("training")` directly on the
`@Configuration` class itself, not just on its `@Bean` methods — `@ControllerAdvice` beans are
`@Component`-meta-annotated and would otherwise be picked up by component-scan regardless of
profile. Omitting the profile wires zero beans; the rest of the app is byte-for-byte as shipped
(confirmed: no code references, no behavioral scoping over real HTTP, when the profile is absent).

To also provision a real per-trainee Epistola tenant (rather than leaving trainees sharing no
Epistola-side isolation at all), set:

```properties
epistola.training.epistola-shared-secret=<epistola-suite's demo-profile shared secret>
```

This reuses epistola-suite's own demo-profile shared-secret mechanism
(`DemoSharedSecretAuthenticationFilter`, gated behind `epistola.demo.enabled=true` on the Epistola
side) — an all-tenant-superuser credential — to create one tenant per trainee without minting a
separate Epistola API key per trainee (`SharedSecretEpistolaTenantProvisioner`). Leave it unset
and tenant provisioning fails loudly instead of silently
(`NotConfiguredEpistolaTenantProvisioner`).

## Who becomes a trainee

A principal carrying a real Keycloak realm role, `ROLE_DEMO`
(`docker/keycloak/valtimo-realm.json`) — an explicit, assigned role, not "any authenticated
non-admin." That would sweep in genuine non-admin, non-trainee users of a mixed-use instance, with
no third category between "admin" and "trainee." Every check in this feature keys off `ROLE_DEMO`
presence alone, never off the absence of any other role.

Two demo accounts are seeded in the local docker-compose Keycloak realm for manual testing:
`trainee1@demo` / `trainee2@demo` (password matches the username), each carrying
`ROLE_USER` + `ROLE_ADMIN` + `ROLE_DEMO`.

## Provisioning: what happens on first request

`TraineeProvisioningFilter` (a `OncePerRequestFilter`, not an `AuthenticationSuccessHandler` — the
test-app runs both `oauth2Login` and `oauth2ResourceServer` simultaneously, and
`AuthenticationSuccessHandler` only fires for the former) does a cheap ownership lookup on every
authenticated request, only running the expensive path below on a miss:

1. Compute a stable case-definition key by hashing the resolved identity (`TraineeIdentity`,
   preferring the JWT `sub` claim, falling back to `.name`). Always hashed, never used verbatim —
   a real Keycloak token can omit `sub` entirely, and an email-shaped identity (`trainee1@demo`)
   fails `CaseDefinitionId.key`'s `[a-zA-Z0-9-]+` pattern.
2. Create the trainee's `PluginConfiguration` (`PluginConfigurationId.newId()`, known up front),
   wrapped in `AuthorizationContext.runWithoutAuthorization { }`, and install the classpath
   catalogs (the demo templates) into the trainee's new, empty Epistola tenant. It uses the same
   `EpistolaCatalogSyncService` that `EpistolaCatalogSyncTrigger` runs at startup; a configuration
   created later never gets that startup run. The import is best effort: a failure is logged and
   provisioning continues. The configuration is `templateSyncEnabled`, so every restart re-syncs
   the trainee's tenant as well.
3. Export the `form-flow-demo` case-definition template and re-import it under the trainee's key
   via Valtimo's own `ExportService`/`ImportService` — `keyOverride`, `nameOverride`, and
   `pluginConfigurationMappings` (remapping the template's plugin-configuration id to the
   trainee's own). Not bespoke duplication code.
4. Finalize the new case definition — this also locks its schema/BPMN as immutable, which is the
   right boundary: trainees get scoped admin over process-links/plugin-config, not over the
   underlying structure.

The sequence is deliberately **not** wrapped in `@Transactional`: Valtimo's own export/import
tolerates per-artifact exceptions that would otherwise poison an ambient transaction. Every step
is retryable instead — a crash mid-flow is a safe partial state, not a corrupt one, and the
ownership-table check plus `ImportService`'s own skip-list (`findAllByFinalTrue()`) make a second
attempt for an already-provisioned trainee a no-op.

**One BPMN wrinkle worth knowing**: `OperatonProcessDefinitionImporter` does not rewrite the
process key — `"form-flow-demo"` stays literal on every clone. Valtimo instead disambiguates
process definitions by `(key, versionTag = "CD:" + caseDefinitionId)`, which is exactly what
process-link/process-document-link lookups already key off. This is a real Valtimo mechanism, not
a gap — but it does mean a bare process-definition **key** is ambiguous across every trainee's
clone; only full process-definition/process-instance/execution/document **ids** are not.

## Authorization model

```
Authorities
  └─ TraineeAdminAuthorityStripFilter removes ROLE_ADMIN from a ROLE_DEMO
     principal server-side (the token keeps it, for the frontend)
        ↓
HTTP layer (Spring Security)
  └─ TrainingHttpSecurityConfigurer widens the admin-gated endpoints a
     trainee needs for their own dossier to hasAnyAuthority(ADMIN, ROLE_DEMO)
        ↓
Data layer (Valtimo PBAC, demo.permission.json)
  └─ ROLE_DEMO: every case type visible; cases, tasks, notes and case
     resources only where createdBy == ${currentUserEmail}
        ↓
Ownership layer (ROLE_DEMO-scoped, genuine admins untouched)
  ├─ TraineeOwnershipInterceptor        — path/query-param-identified requests
  ├─ TraineeOwnershipRequestBodyAdvice  — POST/PUT bodies
  └─ TraineeOwnershipResponseBodyAdvice — list responses, filtered to "owned by me"
        ↓
Hard block (no safe per-trainee scoping exists at all)
  └─ TraineeAdminSurfaceGuardFilter — 403s before Spring Security's
     authorization decision runs, filter position fixed at chain build
```

### Why the token carries `ROLE_ADMIN` but the backend ignores it

A real browser login showed Valtimo's frontend gates its entire admin UI on `ROLE_ADMIN`
**client-side**, with no finer-grained frontend role to widen instead — with `ROLE_DEMO` alone, a
trainee's side-nav had no Admin section at all. So the identity provider still puts `ROLE_ADMIN` in
a trainee's token; the frontend reads roles straight from the token
(`test-app/frontend/src/environments/auth/authentik-config.ts`).

The backend does not honour it: `TraineeAdminAuthorityStripFilter` drops `ROLE_ADMIN` from any
principal that also carries `ROLE_DEMO`, before Spring Security's HTTP gate and before any PBAC
check. That matters because PBAC unions grants across roles, and this app's `all.permission.json`
grants `ROLE_ADMIN` every case, task and note unconditioned (see
[The critical finding](#the-critical-finding-unconditioned-role_admin-pbac)). Without it, no
`ROLE_DEMO` condition could narrow anything.

What a trainee may do then comes from two places:

- **`demo.permission.json`** (PBAC, `ROLE_DEMO`): view every case definition; start a case in any
  of them; view, work on and search only the cases they created
  (`createdBy == ${currentUserEmail}`), and the tasks, notes and case resources of those cases.
- **`TrainingHttpSecurityConfigurer`**: the admin endpoints needed to configure their own dossier,
  widened to `ROLE_DEMO`. The ownership layer below keeps those to their own dossier (read-only for
  shared case types).

`${currentUserEmail}` and `JsonSchemaDocument.createdBy` resolve to the same value in the
`authentik` profile: both come from the token's `email` claim (principal name, and
`OidcUserManagementService.getCurrentUser().email`). `TraineeScopingE2ETest` asserts this.

### Shared case types

A **shared** case type is one this app ships (`config/case`), recognised by Valtimo's own columns:
every version has neither `originalKey` (a trainee's clone has it) nor `createdBy` (a draft
created through `/admin/dossiers` has it). Confirmed on the live demo database. An unknown key is
not shared. Trainees can start and work their own cases in a shared case type and read its
configuration; they cannot change it.

### The ownership layer

`TraineeOwnershipChecks` is the single source of truth all three enforcement points call into, so
the three surfaces (path/query params, request bodies, response bodies) can't drift apart. Each
check resolves a resource to its owning case-definition key and compares it against
`TraineeKeys.caseDefinitionKey(traineeIdentity)` — or, for a self-created dossier (see
[Self-service dossier creation](#self-service-dossier-creation) below), against
`CaseDefinition.createdBy`:

| Resource                           | Owned when                                     | Shared case types              |
| ---------------------------------- | ---------------------------------------------- | ------------------------------ |
| Plugin configuration               | it is the trainee's own (direct id comparison) | read-only (`allowShared`)      |
| Case-definition management surface | the path key is the trainee's dossier          | read-only (`allowShared`)      |
| Process definition                 | it belongs to the trainee's dossier            | read-only (`allowShared`)      |
| Document search                    | the case type is theirs or shared              | yes — PBAC narrows the results |
| Document                           | the trainee created it (`createdBy`)           | same rule, any case type       |
| Task                               | the trainee created its case document          | same rule                      |
| Process instance (force-delete)    | the trainee created its case document          | same rule                      |
| Execution (reconcile)              | the trainee created its case document          | same rule                      |

Configuration of a shared case type is read-only; its **cases** belong to whoever created them.
Every resolver fails closed — an id that doesn't resolve (deleted, or a lookup error) is treated
as not owned, never as owned by default. A mutation under the case-scoped management prefixes that
names no case type at all is refused.

### The hard-block filter

`TraineeAdminSurfaceGuardFilter` blocks every _other_ admin surface real `ROLE_ADMIN` unlocks that
has no per-resource identifier to scope by at all: the PBAC editor itself (a trainee could
otherwise grant themselves any permission), translation management, choice fields, object
management config, global forms/decision-tables CRUD, the case-_unlinked_ "system"
process-definition surface, process migration, raw BPMN deployment, logs, case migration,
dashboard management, and a few sub-resources of this plugin's own admin page (the engine-wide
BPMN validation report and legacy-override form scan — every cloned dossier shares the same
literal process-definition key, so these can't attribute results to one trainee over another even
in principle).

Implemented as a filter (`addFilterBefore`), not more `authorizeHttpRequests` entries: Valtimo
combines every registered `HttpSecurityConfigurer` bean's rules onto one shared,
first-match-wins matcher list, so an _allow_ rule can lose an ordering race against ~80 other
auto-configured beans. A _block_, registered as a filter, has no such race — filter position is
fixed once the chain is built, independent of any bean's `@Order`.

Every blocked entry carries its own specific 403 reason (e.g. _"this manages roles and
permissions for the whole instance"_ vs. _"this operates on an arbitrary id with no way to
confirm it belongs to your own dossier"_), written directly into the response body
(`TraineeRejection.rejectAsForbidden`) — not via `sendError`, whose message Spring Boot's default
error-page handling silently strips.

### Endpoints scoped instead of blocked

A handful of endpoints look like "arbitrary id, nothing to check ownership against" at first
glance but turned out to be resolvable once `ProcessInstanceOwnershipResolver` existed (process
instance → business key → case-definition key; an execution resolves to its process instance
first, then the same chain):

| Endpoint                                                                   | Scoping                                                       |
| -------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `POST /api/v1/process/{processInstanceId}/delete`                          | own only, never the shared template (no read-only form)       |
| `GET .../admin/configurations/{configurationId}/catalogs`                  | own or shared template (read-only)                            |
| `POST .../admin/configurations/{configurationId}/catalogs/{slug}/redeploy` | own only                                                      |
| `GET .../admin/export/{processLinkId}`                                     | own only (resolves via the process-link's process definition) |
| `POST .../admin/pending/{executionId}/reconcile`                           | own only                                                      |

What's left hard-blocked keeps a genuinely different reason each: process migration and raw BPMN
deployment stay "arbitrary target" (a dossier is finalized at provisioning and never gets a second
deployed version to migrate between, so migration has no legitimate trainee use case even though
it's technically resolvable; deployment creates a brand-new process with no existing target at
all), while the validation/legacy-override scans have no per-resource identifier to scope by in
the first place.

### Self-service dossier creation

Beyond the one auto-provisioned dossier, a trainee can create up to 10 more of their own through
Valtimo's own `/admin/dossiers` UI (`POST /api/management/v1/case-definition/draft`) — no longer
hard-blocked. These aren't named by a hash of the trainee's identity (the trainee picks the key
themselves), so they're recognized instead by `CaseDefinition.createdBy` — a real Valtimo column
`CaseDefinitionService.createCaseDefinitionDraft` already populates from the authenticated caller
via `SecurityUtils.getCurrentUserLogin()` (`= authentication.getName()`), confirmed to resolve to
exactly the same value `TraineeIdentity.resolve` uses everywhere else in this feature (both land
on the JWT's `email` claim — see [Provisioning](#provisioning-what-happens-on-first-request)
above). **No new table**: `isOwnCaseDefinition` falls back to querying this existing column only
when the key-hash comparison misses.

`TraineeOwnershipRequestBodyAdvice` enforces two things before the request ever reaches Valtimo's
controller:

- **The cap.** Counts _distinct_ case-definition keys `createdBy` the trainee, not rows — a
  case-definition can have several draft/finalized versions under the same key, which is still one
  dossier. Fetches every case-definition and filters in memory (`CaseDefinitionRepository` is
  Valtimo's own, not ours to extend, and exposes no `createdBy`-filtered query) — fine for a
  demo/training instance, not a production-scale one.
- **Existing-key drafts.** Drafting a new _version_ of an already-existing key (Valtimo's
  `basedOnCaseDefinitionVersion` field) requires already owning that key — otherwise a trainee
  could draft a new version of another trainee's dossier, or of a shared/unrelated case type, by
  naming its key and picking any not-yet-used version tag. This check never counts against the
  cap: it isn't a new dossier.

Once created, ownership of a self-created dossier is recognized everywhere `isOwnCaseDefinition`
already runs — deletion, finalization, the case-definition list view — with no changes needed at
any of those call sites.

## The critical finding: unconditioned `ROLE_ADMIN` PBAC

A dedicated post-implementation security review — driving the app as two real trainee accounts
over real HTTP, not reading the code or trusting unit tests — found that granting trainees real
`ROLE_ADMIN` didn't just widen the admin-_configuration_ surface above. Valtimo's own
`all.permission.json` (its stock PBAC seed) grants `ROLE_ADMIN` **completely unconditioned**
access to 12 PBAC resource types, including `JsonSchemaDocument` and `OperatonTask`, and **PBAC
unions grants across every role a principal carries** — an unconditioned grant on any role a user
has overrides a conditioned/restrictive grant on another role.

Confirmed live: a trainee could fetch a full, unrelated case's document and task — including its
process variables — via `GET /api/v1/document/{id}` / `GET /api/v1/task/{taskId}`, and
`GET /api/v1/task?filter=all` returned tasks mixed across every case type in the instance. The
interceptor-level data-plane scoping closed those two; 9 more unconditioned resource types
(`Note`, `JsonSchemaDocumentSnapshot`, `Dashboard`, `CaseTab`, `SearchField`, `Object`,
`ResourcePermission`, `CaseDefinition` view/view_list, `OperatonExecution`) and the task batch
endpoints stayed open.

**Addressed at the root** by `TraineeAdminAuthorityStripFilter`: a trainee no longer holds
`ROLE_ADMIN` server-side, so none of `all.permission.json`'s unconditioned grants apply to them;
their PBAC rights are only what `demo.permission.json` grants `ROLE_DEMO`. Verified by
`TraineeScopingE2ETest` for documents, tasks and case search (disabling the filter makes it fail).
`JsonSchemaDocumentSnapshot`, `Dashboard`, `Object` and the task batch endpoints now have no
`ROLE_DEMO` grant at all, so PBAC denies them; not separately exercised over HTTP.

**The lesson, if you extend this pattern elsewhere**: granting a real Valtimo role as a
compensating measure for a frontend gate is not free — audit every PBAC grant that role already
carries unconditioned, not just the specific endpoints you set out to widen.

## Known gaps

- **Dossier retention/cleanup is not implemented.** Abandoned dossiers accumulate indefinitely —
  there is no scheduled purge job yet.
- **Admin screens without `ROLE_ADMIN` are not browser-verified.** The endpoints a trainee's own
  dossier editors call were widened from Valtimo's `HttpSecurityConfigurer` rules, not from a real
  browser session. A screen that calls an endpoint not in `TrainingHttpSecurityConfigurer` now
  gets a 403 for trainees; add it there (read-only lookups) or scope it.
- **Case-definition metadata** (`CaseDefinition` view) is granted to `ROLE_DEMO` for every case
  type, because PBAC cannot express "the trainee's own clone" (its key is a hash). Lists are
  filtered by `TraineeOwnershipResponseBodyAdvice`; a direct `GET` of another trainee's
  case-definition settings on the non-management API still returns its metadata (name included),
  never its cases.
- **Keycloak mode is not verified.** `TraineeScopingE2ETest` runs the `authentik` profile. Under
  `keycloak-iam`, `${currentUserEmail}` is looked up through Keycloak's admin API.

## Local testing notes

- Get a token from Keycloak on `8081` with `client_id=valtimo-console`, `grant_type=password`,
  and one of `trainee1@demo`/`trainee1`, `trainee2@demo`/`trainee2`, or `admin`/`admin`.
- This repo's PBAC deployer is changeset-ID-tracked (apply-once, like Liquibase) — a persistent
  local Postgres that already applied older changeset content under the same IDs won't pick up
  renames/content changes without a DB reset.
- **Podman machine clock drift after a host sleep** breaks Keycloak token validation entirely
  (every freshly-issued JWT appears pre-expired to the host-clock-based Valtimo backend) — not
  specific to this feature, but disproportionately easy to hit while iterating on it locally
  since it silently turns into 401s that look like an auth bug in this code. A container restart
  does **not** fix it (containers share the VM's kernel clock, not their own); only
  `podman machine stop && podman machine start` resyncs it.

## External progress checks: the shared-secret filter

Something outside the running app — a monitoring tool, an instructor dashboard, whatever drives
the training facility's own idea of "which trainees have done what" — needs to query Valtimo
directly to check on progress: has a dossier been provisioned, has the demo case been completed,
how many self-created dossiers exist, and so on. This needed a way in that isn't a human logging
in through a browser.

**Deliberately not a Keycloak client-credentials grant**, even though `test-app/backend` already
runs `oauth2ResourceServer` and would have accepted one with zero new Valtimo code — ruled out to
avoid a new piece of Keycloak realm/client configuration to keep in sync. Instead,
`TrainingFacilitySharedSecretAuthenticationFilter` mirrors epistola-suite's own
`DemoSharedSecretAuthenticationFilter`: a single static credential, checked directly inside this
application, entirely inside `test-app/backend`'s own code.

Enable it by setting `epistola.training.facility-shared-secret`; blank (the default) means this
entire access path does not exist. Present the secret in a dedicated header — not `Authorization:
Bearer`, since that scheme is already claimed by Spring's own OAuth2 resource-server JWT filter,
which would otherwise try to decode the static secret as a JWT and fail before this filter got a
chance to run:

```bash
curl -H "X-Training-Facility-Secret: <the configured secret>" \
  http://localhost:8080/api/management/v1/case-definition
```

Grants `ROLE_USER` + `ROLE_ADMIN` — the whole API, deliberately, matching the "expose the whole
API" decision behind this rather than a bespoke read-only "progress" endpoint that would need a
new field every time the definition of "progress" changes. **Deliberately no `ROLE_DEMO`**: that
role means "trainee," which would provision this credential a pointless dossier of its own on
first use and then scope every other check in this package down to just that dossier — the
opposite of the cross-trainee visibility a monitoring tool needs.

`TrainingFacilitySharedSecretAuthenticationFilterTest` is a plain unit test — it calls `doFilter`
directly against a mocked request, which proves the filter's own logic but not that a real request
actually gets past Valtimo's real `authorizeHttpRequests` gate once it sets the `SecurityContext`.
`TrainingFacilitySharedSecretAuthenticationFilterE2ETest` closes that gap: `@AutoConfigureMockMvc`
wires `MockMvc` against the real, registered filter chain — Spring Security included, unlike every
other E2E test in this package, which calls controller/service beans directly and never touches
the servlet filter chain at all — and asserts a real HTTP request either does or does not pass the
same gate a real client would hit: no header or the wrong secret is rejected (403), the correct
secret reaches a real `ROLE_ADMIN`-gated endpoint (200). Manual live verification (curl against the
running dev instance) additionally confirmed cross-trainee data is actually returned and that no
dossier gets auto-provisioned for the credential — the automated test proves the authorization
decision, not the response content.

Inherits the exact same access-scope caveat as granting trainees `ROLE_ADMIN` does (see
[The critical finding](#the-critical-finding-unconditioned-role_admin-pbac) above): full admin
access, not read-only, and there is currently no narrower role that would let it query progress
without also being able to change things. Treat the secret accordingly.

## Verification checklist

After changing anything under `training/security/`:

- [ ] A trainee's own case-definition/process-link/plugin-configuration/document/task access
      still works.
- [ ] Cross-trainee access to the same resource types is a 403 with a specific reason, not a
      generic "forbidden" or a silent empty result.
- [ ] Shared case types stay visible read-only, reject every configuration change from a
      trainee, and show each trainee only the cases they created.
- [ ] `TraineeScopingE2ETest` passes, and still fails with `TraineeAdminAuthorityStripFilter`
      disabled.
- [ ] Every admin surface not widened for trainees is 403 for a trainee and 200 for a genuine
      `ROLE_ADMIN`-only account.
- [ ] `TrainingWebConfig`'s registered path patterns still cover every endpoint family
      `TraineeOwnershipInterceptor` handles — this has drifted out of sync with the interceptor's
      own branches twice before, each time leaving Spring Security correctly widening the HTTP
      gate but with _no ownership check running at all_ for the newly-widened path.
- [ ] The `training` profile absent still leaves the app byte-for-byte as shipped (no beans, no
      behavioral scoping).
