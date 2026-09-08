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
   wrapped in `AuthorizationContext.runWithoutAuthorization { }`.
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
HTTP layer (Spring Security)
  └─ TrainingHttpSecurityConfigurer widens ~45 admin-gated endpoints
     to hasAnyAuthority(ADMIN, ROLE_DEMO), ordered ahead of Valtimo's
     own module configurers for the same paths
        ↓
Ownership layer (ROLE_DEMO-scoped, real ROLE_ADMIN untouched)
  ├─ TraineeOwnershipInterceptor        — path/query-param-identified requests
  ├─ TraineeOwnershipRequestBodyAdvice  — POST/PUT bodies
  └─ TraineeOwnershipResponseBodyAdvice — list responses, filtered to "owned by me"
        ↓
Hard block (no safe per-trainee scoping exists at all)
  └─ TraineeAdminSurfaceGuardFilter — 403s before Spring Security's
     authorization decision runs, filter position fixed at chain build
```

### Why trainees carry real `ROLE_ADMIN`

The original design deliberately avoided this, specifically so a trainee could never pass
Valtimo's own `hasAuthority(ADMIN)` gates by construction. That held until a real browser login
showed Valtimo's frontend gates its entire admin UI on `ROLE_ADMIN` **client-side**, with no
finer-grained frontend role to widen instead — with `ROLE_DEMO` alone, a trainee's side-nav had no
Admin section at all. Satisfying the frontend meant granting the real authority, and the backend
fully compensating for everything that grant would otherwise open up — everything below exists
because of this one pivot.

### The ownership layer

`TraineeOwnershipChecks` is the single source of truth all three enforcement points call into, so
the three surfaces (path/query params, request bodies, response bodies) can't drift apart. Each
check resolves a resource to its owning case-definition key and compares it against
`TraineeKeys.caseDefinitionKey(traineeIdentity)` — or, for a self-created dossier (see
[Self-service dossier creation](#self-service-dossier-creation) below), against
`CaseDefinition.createdBy`:

| Resource                           | Resolved via                                     | Shared `form-flow-demo` template allowed? |
| ---------------------------------- | ------------------------------------------------ | ----------------------------------------- |
| Plugin configuration               | direct id comparison                             | read-only (`allowShared`)                 |
| Case-definition management surface | path variable is the key directly                | read-only (`allowShared`)                 |
| Process definition                 | `ProcessDefinitionOwnershipResolver`             | read-only (`allowShared`)                 |
| Document                           | `DocumentOwnershipResolver`                      | **never** — live case data, not structure |
| Task                               | `TaskOwnershipResolver` → process instance → doc | **never**                                 |
| Process instance (force-delete)    | `ProcessInstanceOwnershipResolver`               | **never** — mutation, no read-only form   |
| Execution (reconcile)              | `ProcessInstanceOwnershipResolver`               | **never** — mutation                      |

The line drawn for `form-flow-demo` sharing is deliberate: it's a live case type real staff and
other automated tests can create genuine instances under, so "shared" stops at read-only
**structure** (settings/schema/config) and never extends to actual case **data**. Every resolver
fails closed — an id that doesn't resolve (deleted, or a lookup error) is treated as not owned,
never as owned by default.

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
`GET /api/v1/task?filter=all` returned tasks mixed across every case type in the instance. This
existed from the moment `ROLE_ADMIN` was first granted until the data-plane scoping (the
`Document`/`Task` rows in the table above) closed it.

**Not yet closed** — tracked, not silently assumed safe: 9 more unconditioned `ROLE_ADMIN`
resource types found in the same review pass (`Note`, `JsonSchemaDocumentSnapshot`, `Dashboard`,
`CaseTab`, `SearchField`, `Object`, `ResourcePermission`, plus `CaseDefinition` view/view_list and
`OperatonExecution`'s non-`create` actions), and the task batch endpoints
(`batch-assign`/`batch-complete`).

**The lesson, if you extend this pattern elsewhere**: granting a real Valtimo role as a
compensating measure for a frontend gate is not free — audit every PBAC grant that role already
carries unconditioned, not just the specific endpoints you set out to widen.

## Known gaps

- **Dossier retention/cleanup is not implemented.** Abandoned dossiers accumulate indefinitely —
  there is no scheduled purge job yet.
- The 9 unconditioned `ROLE_ADMIN` resource types and task batch endpoints above.
- `TraineeOwnershipScopingE2ETest` — an automated, two-trainee, real-HTTP test covering everything
  manual/live verification has exercised by hand across this feature's development — is not
  written. Manual verification has substituted for it so far.
- The "own OR shared-template" PBAC pattern (where it appears) assumes Valtimo unions multiple
  permission entries for the same resourceType/action/role — this deploys without error, but isn't
  proven correct for actual query-layer filtering via a dedicated test.

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
opposite of the cross-trainee visibility a monitoring tool needs. Verified live: with no header or
the wrong secret the request is anonymous (403, same as any unauthenticated call); with the
correct secret it reads every case-definition and task across every trainee, and no dossier gets
auto-provisioned for it in the process.

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
- [ ] The shared `form-flow-demo` template stays visible read-only, and rejects every mutation
      attempt from a trainee.
- [ ] Every newly-reachable admin surface (via the `ROLE_ADMIN` grant) is still 403 for a trainee
      while remaining 200 for a genuine `ROLE_ADMIN`-only account.
- [ ] `TrainingWebConfig`'s registered path patterns still cover every endpoint family
      `TraineeOwnershipInterceptor` handles — this has drifted out of sync with the interceptor's
      own branches twice before, each time leaving Spring Security correctly widening the HTTP
      gate but with _no ownership check running at all_ for the newly-widened path.
- [ ] The `training` profile absent still leaves the app byte-for-byte as shipped (no beans, no
      behavioral scoping).
