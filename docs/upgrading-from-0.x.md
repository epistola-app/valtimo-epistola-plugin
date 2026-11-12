# Upgrading from 0.x to 1.0

A checklist for moving an installation from plugin **0.21.x** to **1.0** (coming from an older 0.x,
upgrade to 0.21 first, or read its [CHANGELOG](../CHANGELOG.md) entries on the way). Each item says
what changed, who it affects and what to do. The [CHANGELOG](../CHANGELOG.md)'s _Upgrading_ sections
for `1.0.0-alpha.1`, `1.0.0-beta.1` and the release you install have the full detail.

The upgrade was rehearsed on 0.21.0 with Suite 1.5.1, moved to 1.0 with a Suite built from `main`
(contract 2.0): a process that was running finished on its old definition, a generation submitted
just before the upgrade was delivered after it, and letters generated before it still download.

## Before the upgrade

- [ ] **Plan the Epistola Suite upgrade together with the plugin.** 1.0 speaks Epistola contract
      2.0, which no 1.x Suite serves (it refuses the request with 406/415), and 0.21 speaks contract 1.x.
      Upgrade the Suite to 2.0 and the plugin to 1.0 in the same window. See
      [COMPATIBILITY.md](../COMPATIBILITY.md) for the contract each build needs.
- [ ] **Give the plugin's API key the `document-generator` role** beside `content-viewer`. From
      contract 2.0, downloading a generated document is granted by `document-generator`. A key that
      already has both is fine.
- [ ] **Valtimo stays as it is.** 1.0 is built on the same Valtimo (13.50) as 0.21.

## Catalogs

- [ ] **Process links keep their catalog.** A link saved under 0.x holds a bare catalog key;
      contract 2.0 names a catalog `registry/namespace/key`. The plugin resolves the bare key among the
      tenant's catalogs and, when several have it, takes `local/<tenant key padded to 3>/<key>`, which
      is the name the Suite's 2.0 upgrade gives a tenant's own catalogs. If it still cannot choose,
      the step is refused (409) with the full names to choose from. The admin page's usage overview
      lists each such link with what it resolves to: open it and pick the catalog again to store the
      full name. Not required, but it removes the guess.
- [ ] **Bundled classpath catalogs are installed under `local/<tenant>`**, so the first sync adds to
      the migrated catalog instead of installing a second one. Override with
      `epistola.catalog-sync.registry` / `epistola.catalog-sync.namespace` if yours live elsewhere.
      A changed catalog needs a new `release.version`: the same version with other content is refused.
- [ ] **An import no longer deploys to an environment.** If a process link or a letter composer
      names an environment, deploy the imported release to it in Epistola, or that link renders
      nothing. Links without an environment render the catalog's highest release, as before.

## Process links and processes

- [ ] **Decide per generate step whether it is `asyncBefore`** (_Asynchronous before_ in the
      modeler), for `generate-document` and for a composed letter. This is unchanged from 0.x. With
      it, a failure becomes an incident an administrator retries. Without it, the person completing
      the previous task sees Valtimo's generic error and their submit rolls back. Both are valid;
      [process-variables.md](process-variables.md) compares them, and `asyncBefore` is the
      recommended default. The admin page's usage overview now notes a generate step without it. A
      process already running keeps the definition it started on, so a boundary you add protects new
      instances only.
- [ ] **Check v1 links whose environment or variant is an expression.** An expression that produces
      no value (`$pv.omgeving` on a case without that variable, `someEnv` without quotes) used to fall
      back silently to the plugin's default environment or the template's default variant. It now
      raises an incident at the step. Write the fallback into the expression:
      `$pv.omgeving ?: $plugin.defaultEnvironmentId`, or `?: ""` for deliberately none (the default
      variant). **Who is affected:** only links saved as `actionConfigVersion: 1` (saved in the editor
      since 0.16). Unversioned (v0) links keep the old reading until they are opened and saved. A
      variant picked from the dropdown is a string literal and always has a value. There is no
      automatic check; look through the v1 links that use `$pv`, `$doc` or `$case` in these fields.
- [ ] **Nothing to do for statuses.** Contract 2.0 sends `completed` and `in-progress`; the plugin
      still writes `COMPLETED` and `IN_PROGRESS`, so gateways keep working.
- [ ] **Nothing to do for refused generations.** As in 0.21, a generation that cannot start (a
      missing template, release, variant or environment, or an expression that fails) raises an
      incident at the step behind `asyncBefore`. Retrying it after fixing the cause runs the step
      again. What changed is the diagnosis: it now says what is missing. Look for it in the
      incident's **stack trace**: the incident's own message is Valtimo's _"Failed to invoke event
      listener method"_, a Valtimo defect described in
      [valtimo-upstream-issues.md](valtimo-upstream-issues.md).

## Forms

- [ ] **Remove the retry form, if you still use it.** It was deprecated in 0.21 and is gone in 1.0:
      the `epistola-retry-form` component shows nothing, and `generate-document` no longer reads
      `epistolaEditedData`, so a process that still sets it gets the letter its mapping produces.
      Correct the case and generate again instead, as [user-task-fallback.md](user-task-fallback.md)
      describes. `epistola.retry-form.enabled: true` only logs a warning now.
- [ ] **Letter composer settings migrate on their own.** They move to schema version 4 (environment
      and ranges as JSONata). Older settings are read as the literals they meant; opening a composer
      in the form builder brings it to 4. The composer is no longer alpha: from 1.0 what it stores is
      covered by 1.x.
- [ ] **Nothing to do for document previews.** A preview whose input overrides are still in the
      format from before JSONata keeps working; saving its form in the builder converts it. The
      admin page no longer lists such forms.

## After the upgrade

- [ ] Open the plugin's **admin page**: the health tab shows each connection's Suite version and
      contract, and the usage overview shows bare catalog keys and catalogs not deployed to a link's
      environment, and notes generate steps without `asyncBefore`.
- [ ] Generate one letter per catalog in each environment you use.

## Rolling back

Going back to 0.21 also means going back to a 1.x Suite. Letters composed after the upgrade are
stored at a composer schema that 0.21 refuses (with a message naming both versions); their generate
task waits until the upgrade is redone. Rolling the Suite back is governed by the Suite's own
upgrade notes.

If you scripted against the plugin's REST API, note that the admin catalog redeploy now reports the
installed release (`catalogId`, `newlyInstalled`, `total`) instead of per-resource counts,
`GET /admin/versions` no longer has `epistolaServerVersion`, `GET /retry-form` and
`GET /admin/forms/legacy-override` are gone, and `POST /preview` ignores `overrides`.
