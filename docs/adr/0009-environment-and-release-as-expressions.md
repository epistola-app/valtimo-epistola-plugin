# ADR 0009 — Environment and release: two expressions, one release to design against

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** Epistola plugin maintainers
- **Related:** ADR 0006 (letter composer configuration), ADR 0007 (one generate action or two),
  `docs/generate-document-action-versioning.md`, `docs/letter-composer.md`,
  `EpistolaServiceImpl.resolveForRead`, `GenerateDocumentActionV1Parser`, `ComposerParser`,
  `generate-document-configuration.component.ts`, `letter-set-builder.component.ts`

## Context and problem statement

Contract 2.0 renders a catalog from a **release**. Which release is decided by two inputs on a
generate request:

- `environmentKey`: the environment renders what is deployed to it — its deployed draft, else its
  highest deployed release.
- `versionRange`: an npm range (`1.4.0`, `^1.4.0`, `>=1.3 <2`); the highest release that satisfies
  it, of those deployed when an environment is named too.

Suite supports both from **2.0.0-alpha.1** (`CatalogReleaseSelector`); no 1.x release has version
ranges. This plugin already requires a 2.0 Suite, so relying on them raises no floor.

A process link (the generate action) and a letter set (the composer) store both. Until now they
were not alike:

| Field         | Generate action                              | Composer letter set |
| ------------- | -------------------------------------------- | ------------------- |
| environment   | JSONata, evaluated at run time               | plain text          |
| version range | plain text, "deliberately not an expression" | plain text          |

The range was kept plain because the configurator **reads the template through it**: the template
list, its fields (which drive the data mapping), its variants and the catalog's attributes all come
from the release the range selects. An expression is only known at run time, so the configurator
could not know what to read.

That reasoning broke down in practice, and a defect showed where:

1. **Picking an environment the catalog is not deployed to errors.** The configurators narrowed the
   catalog list to what the environment serves, but keep a catalog already chosen (so changing only
   the environment does not wipe the template and mapping). Every lookup for that catalog — templates,
   releases, attributes, variants, fields — then resolved the release on its own, got a 404 with an
   empty body, and showed its own generic, untranslated error. One fact ("not deployed here") failed
   five times; the one message that explained it was buried. The backend logged nothing: a 404 is a
   response, not an exception.
2. **Configuring before deploying is normal.** A team configures the production link while the
   catalog is still only on staging. Narrowing the list made that impossible.
3. **The environment was already an expression**, so the configurator already had to read
   _something_ when it could not know the environment. It read every catalog from its highest
   release (`anyEnvironment`). The range is no different in kind.

## Decision drivers

- A link must be configurable before its catalog is deployed where it will render.
- One underlying problem is reported once, translated, at the field it concerns.
- Every lookup a configurator makes reads the **same** release, so the template list, its fields
  and its variants can never come from different releases.
- Environment and release are expressed the same way, in both the generate action and the composer.
- What runs is unchanged: generation sends the environment and the range and lets the server decide,
  and refuses loudly (on the case) when nothing matches.

## Decision

### 1. Environment and release are both JSONata

`versionRange` becomes a JSONata expression, evaluated at generation time with the same `$doc`,
`$pv` and `$case` context as `environmentId`. A fixed range is a string literal: `"^1.4.0"`. The
release suggestions write the quotes, as the environment suggestions already do.

Not a bare range with expression detection: a bare range is not reliably told apart from JSONata.
`1.4` is valid JSONata (a number), `x` and `latest` are path lookups, `*` is a wildcard, while `^1.4`
and `1.4.0` are not JSONata at all. A field that is "a range unless it looks like an expression"
guesses, and guesses silently. This is the same rule v1 already applies to every other scalar
(`docs/generate-document-action-versioning.md`).

An expression's result must be a **string** that is a range; it is checked when evaluated. An empty
or null result means no range (the environment's draft, else its highest release). Anything else
fails the generation with a message naming the field, recorded on the case like any other expression
failure. A number is refused rather than converted: the bare `1.0` evaluates to the number 1, which
as a range would mean `1.x` instead of `1.0.x`.

Each field therefore reads one of three ways, and the configurator scopes its lookups by them:

| Field value          | Environment                 | Release                                   |
| -------------------- | --------------------------- | ----------------------------------------- |
| empty                | the configuration's default | the environment's draft, else its highest |
| a string literal     | that environment            | that range                                |
| any other expression | any (known at run time)     | any (known at run time)                   |

### 2. Resolve the release once, then read only that

A configurator asks one question per catalog: **which release do I design against?**

```
GET /api/v1/plugin/epistola/configurations/{id}/design-scope
    ?catalogId=…&environmentId=…|anyEnvironment=true&versionRange=…|anyRange=true
→ {
    "catalogId": "local/demo/municipality-demo",   // qualified, also for a 1.x bare key
    "versions":  ["6.9.0", "6.8.1"],               // suggestions for the release field
    "version":   "6.9.0",                          // what the mapping is based on; null for a draft
    "draft":     false,
    "environment": "staging",                       // the environment it was read in, or null
    "problems":  ["NOT_DEPLOYED"],
    "read":      { "environmentId": null, "versionRange": "6.9.0" }
  }
```

`read` is the selection every further lookup sends — templates, template fields, variants and
attributes. It is always concrete: an environment's deployed draft, or an **exact** version. So the
lookups agree on the release by construction, and a failure of the selection is reported by this one
call, not by each lookup. It replaces `/releases`, whose list and selected version it contains.

Resolution, in order, falling back rather than failing:

1. With a literal environment: what that environment renders for the range. If the catalog is not
   deployed there → `NOT_DEPLOYED`; if it is, but no deployed release satisfies the range →
   `RANGE_NOT_DEPLOYED`. Either way, continue with the catalog-wide step.
2. Catalog-wide: the highest release satisfying the range. If none does → `RANGE_UNSATISFIED`, and
   continue without the range.
3. Catalog-wide without a range: its highest release, else its newest draft.

What still fails outright: a catalog that does not exist (404), a bare key that is ambiguous (409),
and a literal range that is not a range (400, which also blocks saving, as before).

**Problems are warnings.** A link whose catalog is not deployed to its environment can be saved; it
will not render there until deployed, and the warning says so. Generation itself is strict and
unchanged.

### 3. The catalog list is not narrowed

The configurators list **every** catalog. With a literal environment, each carries whether it is
deployed there (`CatalogInfo.deployedToEnvironment`, null without one), and the ones that are not are
marked in the list. Choosing one shows the `NOT_DEPLOYED` warning under the field.

### 4. Say what the mapping is based on

The release field always states the release the configurator designed against — "based on release
6.9.0", "based on the draft deployed to staging" — and why, when it fell back. When either field is
an expression, that is the highest release (of the range, or of the environment) and it is an
assumption the author can now see.

A **sample picker** (choose a release to design against when the expression can reach several) was
considered and left out. An expression such as `$case.spoed ? "production" : "staging"` can reach a
release whose fields differ from the one designed against; generation then refuses the letter with
a message on the case. That is an edge case, the runtime already catches it, and stating the basis
makes the assumption visible. Revisit if it is hit.

### 5. The composer follows the same model

`letterSet.environmentId`, `letterSet.versionRange` and `templates[].versionRange` become JSONata,
evaluated during prepare with the case context, and the **resolved** values go onto the composed
letter, as today — `generate-dynamic-document` cannot consult the composer.

This changes a stored shape, so `ComposerSchema.CURRENT` goes to **4** (backend and
`composer-schema.ts`). A component saved at schema 3 or earlier stores plain values; the parser reads
those as literals, so existing letter sets keep working without being re-saved. The letter-set
builder writes schema 4. The letter on the process variable keeps plain resolved values.

### 6. The generate action changes within v1

`versionRange` shipped as a plain range in v1, in `1.0.0-alpha.1` and `1.0.0-beta.1` only — both
pre-releases — and no installation uses it. It changes meaning **within v1** rather than adding a
v2: a stored value is parsed as JSONata, and one that is not (`^1.4`, `1.4.0`) is refused with a
message showing the literal form (`"^1.4"`). Of the bare ranges that do parse, a number (`1.4`)
is refused by the string rule above; `x`, `*` and `latest` evaluate to nothing, which reads as no
range, and that is also what each meant as a range (any release, the highest). None is silently read
as something it did not mean. Verified against the JSONata parser for `1.4`,
`1.4.0`, `^1.4`, `1.x`, `x`, `*`, `latest`, `~1.2` and `>=1.3 <2`.

## Consequences

- One request replaces `/releases` and narrows nothing; the read endpoints are unchanged and take
  the selection it returns.
- The five generic load errors give way to one translated warning per problem.
- A link can be prepared ahead of deployment, at the cost that nothing stops it being saved while it
  cannot render yet — the warning is the only guard before run time.
- When either field is an expression, the mapping is designed against one release and may not fit
  every release the expression reaches. This is visible, not prevented.
- The composer's stored shape moves to schema 4; an older plugin refuses a schema-4 letter set with
  the existing "newer than I read" message rather than half-reading it.
