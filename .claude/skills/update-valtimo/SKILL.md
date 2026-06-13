---
name: update-valtimo
description: Upgrade this plugin's Valtimo platform dependency to the latest (or a specified) version. Always reads the Valtimo/GZAC changelog to determine what changed and assess impact on the plugin BEFORE bumping. Use when the user wants to update/upgrade Valtimo, bump the Valtimo version, move to the latest Valtimo release, or check what a new Valtimo release means for this plugin.
---

# Update Valtimo

Upgrades the `valtimo-epistola-plugin` to a newer Valtimo platform version. The defining feature of this skill is the **changelog-driven impact review**: it always reads the Valtimo release notes for every version between current and target, maps each change against the plugin's known Valtimo integration points, and reports the impact **before** touching any file.

## Arguments

- `$ARGUMENTS` — optional target version (e.g. `13.32.0` or `13.32.0.RELEASE`). If omitted, the skill resolves the latest published Valtimo version.

## Behaviour contract

- **Report first, then apply.** After the impact report (step 3), STOP and wait for the user's go-ahead before editing any file.
- **Confirm the resolved target** (step 1) before doing the changelog work.
- **Never commit or push** without explicit user permission (per global instructions).
- Backend version format is `X.Y.Z.RELEASE`; frontend (`@valtimo/*`) is `X.Y.Z` (no suffix). Keep them in lock-step.

---

## Step 1 — Resolve current → target

1. Read the current version from `gradle/libs.versions.toml`:
   ```
   grep -nE '^valtimo =|^spring-boot =|^kotlin =|^epistola-client =' gradle/libs.versions.toml
   ```
   `valtimo = "13.21.0.RELEASE"` is the source of truth for the current version.
2. Resolve the **target**:
   - If `$ARGUMENTS` is given, normalise it (`13.32.0` → backend `13.32.0.RELEASE`, frontend `13.32.0`).
   - Otherwise find the latest from the authoritative sources (the merged `valtimo-platform/valtimo` GitHub repo has **no** releases — do not use it):
     - **Backend** — Maven Central: `https://central.sonatype.com/artifact/com.ritense.valtimo/valtimo-dependency-versions/versions` (or query `https://search.maven.org/solrsearch/select?q=g:com.ritense.valtimo+AND+a:valtimo-dependency-versions&core=gav&rows=5&wt=json`).
     - **Frontend** — npm: `npm view @valtimo/components version`.
     - Cross-check against the GZAC release overview (step 2 source 1) for the headline latest version.
3. Print `current → target` (e.g. `13.21.0 → 13.32.0`) and the resolved Spring Boot / Java / Gradle / Kotlin requirements you expect to check. **Ask the user to confirm the target** before continuing.
4. If current == target, report "already up to date" and stop.

## Step 2 — Fetch the changelog (always)

WebFetch each source and extract every release entry **strictly between current (exclusive) and target (inclusive)**. For a multi-minor jump, enumerate each intermediate version.

1. **Primary release overview** — `https://docs.gzac.nl/product-management/releases`
   (prose per-release feature/fix bullets; latest version headline).
2. **Developer migration / breaking-change notes** — `https://docs.valtimo.nl/release-notes/release-notes`. It supports a `?ask=` query mechanism, e.g.
   `https://docs.valtimo.nl/release-notes/release-notes.md?ask=breaking%20changes%20and%20migration%20notes%20between%2013.21%20and%2013.32`
3. If either source is thin on a given version, also check `https://docs.valtimo.nl/llms.txt` for deeper per-version links.

Collect: new features, bug fixes, **breaking changes**, deprecations, and the required Spring Boot / Java / Gradle / Angular versions for the target.

## Step 3 — Produce the impact report, then STOP

For each changelog entry, classify it against the plugin's Valtimo integration points and tabulate:

| Change (version) | Verdict | Affected plugin area / file | Rationale |

Verdicts: **No impact** / **Review** / **Breaking — action needed**.

Plugin integration points to map against (derived from `CLAUDE.md`):

- **Plugin SDK** — `@PluginAction`, `@PluginProperty`, plugin definition/category APIs; frontend `@valtimo/plugin`. (`backend/plugin/.../EpistolaPlugin*`, `frontend/plugin/.../epistola.specification.ts`)
- **PBAC / authorization** — `OperatonTask:VIEW`, custom resource types, `PermissionDeployer`, permission JSON schema (`config/epistola/permission/*.permission.json`), `EpistolaAdministration:MANAGE`.
- **Process-link** — process-link CRUD endpoints, `@valtimo/process-link`, the `ROLE_ADMIN` gate (no `ProcessLink` PBAC action existed in 13.21 — check if that changed).
- **Result collector / message correlation** — contract `ResultCollector`, BPMN message correlation (`EpistolaResultCollectorRunner`, `EpistolaMessageCorrelationService`).
- **Form / Formio** — form auto-deploy aspect, Formio custom component registration, `@valtimo/components` Formio APIs.
- **Case / document** — business-key/document-UUID binding, task → process-instance → business-key resolution.
- **Frontend framework** — Angular version implied by `@valtimo` (currently Angular 19), `@valtimo/security`, `@valtimo/shared`.
- **Spring Boot / Java** — auto-configuration changes and deprecations from the implied Spring Boot bump.

Also report:

- Target Valtimo's required **Spring Boot / Java / Gradle / Kotlin** vs. what this repo pins, and which need to move.
- **Epistola client** (`epistola-client = "0.6.0"`) — flag if any Valtimo change suggests a compatibility concern. Do **not** auto-bump it.

**Then pause.** Present the report and explicitly ask whether to proceed with the bump. Do not edit anything until the user approves.

## Step 4 — Apply version bumps (after approval)

Edit every version-bearing file. Confirm each match first with the greps shown.

| File                                       | What to change                                                                                       |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| `gradle/libs.versions.toml`                | `valtimo = "<target>.RELEASE"`; bump `spring-boot` / `kotlin` only if the target Valtimo requires it |
| `frontend/plugin/package.json`             | all `@valtimo/*` in `dependencies` (exact) and `peerDependencies` (`^`) → frontend target            |
| `test-app/frontend/package.json`           | all `@valtimo/*` (exact pins) → frontend target                                                      |
| `package.json` (root)                      | the `@valtimo/*` devDeps → frontend target                                                           |
| `.mise.toml`                               | `java` / `gradle` only if the target requires a newer toolchain                                      |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` Gradle version — keep in sync with `.mise.toml`                                    |
| `build.gradle.kts`                         | `JavaLanguageVersion.of(NN)` — keep in sync with `.mise.toml` `java`                                 |

Useful greps to find every site:

```
grep -rn '@valtimo/' package.json frontend/plugin/package.json test-app/frontend/package.json
grep -nE '^valtimo =|^spring-boot =|^kotlin =' gradle/libs.versions.toml
grep -nE 'gradle|java' .mise.toml
```

Do not touch `epistola-client` unless the changelog/build forces it (flag it instead).

## Step 5 — Build & fix: backend

`gradlew` is a mise shim (delegates to `mise exec -- gradle`; toolchain in `.mise.toml`).

```
./gradlew :backend:plugin:test
```

Fix compile/test breakages using the impact report as the guide. Common upgrade hotspots: PBAC API signatures, plugin-SDK annotations, process-link/process-document service APIs, Spring Boot auto-config changes.

## Step 6 — Build & fix: frontend

Plugin must build **before** test-app (per `CLAUDE.md` build order):

```
cd frontend/plugin && pnpm install && pnpm build && pnpm test
cd ../../test-app/frontend && pnpm install && pnpm build
```

Fix breakages. Watch for Angular major bumps (template/API changes) and `@valtimo/components` Formio API changes.

## Step 7 — Update CHANGELOG.md

Add an entry under `## [Unreleased]` (Keep-a-Changelog prose style, matching existing entries), e.g. under `### Changed`:

> **Upgraded Valtimo from `<current>` to `<target>` (backend + frontend).** Also bumped Spring Boot `…` and Gradle `…` to match. <One line per migration step applied / breaking change handled.>

## Step 7b — Update COMPATIBILITY.md

Update the Valtimo compatibility matrix in `COMPATIBILITY.md`:

- Set the **tested-against** version on the current/top row to `<target>`, or add a new row if this bump corresponds to a new plugin-version baseline.
- Revisit the **compatible range** column in light of the changelog reviewed in step 3: widen it if the plugin is expected to keep working across more 13.x versions, or narrow it if a confirmed incompatibility was found. The range is a judgement — do not just copy the pin.
- Keep the two-form convention (backend `X.Y.Z.RELEASE`, frontend `X.Y.Z`).

## Step 8 — Format & verify gates

Run the full gate set from `CLAUDE.md`:

```
pnpm format            # auto-fix .md/.json/.ts/.yaml
pnpm format:check      # CI gate
./gradlew :backend:plugin:test
cd frontend/plugin && pnpm test
pnpm build             # from repo root — must succeed
```

All must pass before the upgrade is considered done.

## Step 9 — Summarize

Report:

- Final versions (Valtimo backend + frontend, and any toolchain bumps).
- Files changed.
- Breakages encountered and how they were fixed.
- Unresolved/follow-up items (e.g. Epistola client compatibility that needs a separate bump, or a deferred migration).
- Remind the user that committing/pushing and releasing are separate steps (`/release`) and need their explicit go-ahead.
