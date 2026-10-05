# ADR 0007 — One generate action, or two

- **Status:** Accepted — Option A, with the configuration-source refactor taken on its own
- **Date:** 2026-10-05
- **Deciders:** Epistola plugin maintainers
- **Related:** ADR 0006 (letter composer configuration), `EpistolaPlugin`, `GENERATING_ACTION_KEYS`, `RetryFormService`, `EpistolaAdminService`, #154, `docs/letter-composer.md`

## Context and problem statement

The plugin has two actions that submit a generation request to Epistola:

|           | **Generate Document**              | **Generate Dynamic Document** |
| --------- | ---------------------------------- | ----------------------------- |
| catalog   | `@PluginActionProperty catalogId`  | from a process variable       |
| template  | `@PluginActionProperty templateId` | from a process variable       |
| variant   | `variantId` / `variantAttributes`  | from a process variable       |
| data      | `dataMapping` — JSONata, per case  | from a process variable       |
| released? | yes                                | **no**                        |

The second was built for the letter composer, which assembles a document in the browser and leaves
it on a process variable (ADR 0006). It turned out to be more general than that: **anything** that
can set a process variable can drive it — an earlier service task, an integration, an API caller —
and a test pins that (`rendersALetterNoComposerProduced`). The composer is one producer, not the
only one.

That generality raised the question this ADR exists for. Naming the second action was hard — three
attempts — and the difficulty is a symptom: two actions that differ only in _where their
instructions come from_ are hard to tell apart in a picker. The alternative is **one action with a
source**, where `generate-document` optionally reads catalog, template, variant and data from a
process variable instead of from its own configuration.

Deciding now is cheap. The second action is unreleased, so it can be removed without migration; the
first is released, so adding an optional source to it is additive. That symmetry will not last.

## Option A — Keep two actions

### For

- **The configurators share almost nothing.** `generate-document-configuration.component.ts` is 893
  lines, plus its HTML, SCSS, a 202-line config-version module, an editor adapter and a config util
  — roughly 1,800 lines. In variable mode essentially all of it is hidden: catalog picker, template
  picker, variant modes, the mapping builder and its completeness analysis. The dynamic
  configurator is 117 lines, and that simplicity is the point.
- **Two clear titles beat one shape-shifting form.** An author picking from a list answers "do I
  choose the template, or does the process?" once. A radio button that hides nine of eleven fields
  answers the same question, later and less visibly.
- **The shared machinery is already abstracted.** `GENERATING_ACTION_KEYS` is the single list that
  catch-event correlation, the deployment validator and the admin usage overview read, pinned by
  `EpistolaGeneratingActionsTest` so a new action cannot be forgotten. The marginal cost of a second
  action is one entry in it.
- No `actionConfigVersion` bump, and no migration for stored links.

### Against

- Two actions to explain, document and keep in step.
- The naming difficulty does not go away; it is managed by a description that says "unlike Generate
  Document, where the author pins the template".

## Option B — One action with a source

### For

- **It dissolves the naming problem** rather than managing it. One action, one entry in the picker,
  one thing to document.
- **Mode-switching is already the house style.** Variant selection inside `generate-document`
  already has `'explicit' | 'attributes'` modes. "Where do the instructions come from" is arguably
  one more such property.
- **Retry might stop being a special case.** This is the strongest argument and it was nearly
  missed. `RetryFormService` rebuilds a failed generation's form from _the process link's_ template
  and mapping, so it covers `epistola-generate-document` only — a composed letter that fails has no
  retry path at all, and the employee re-composes from scratch (#154). Everything downstream of
  "submit" is identical for both actions; it is the configuration lookup that differs. One action
  with a source makes that lookup one branch in one place instead of a second path that has to be
  built and then kept in step.
- Backward compatible in the right direction: additive on the released action, free on the
  unreleased one.

### Against

- The configurator becomes two near-disjoint UIs behind a switch, in a component that is already
  the largest in the plugin.
- `actionConfigVersion` would need a bump and a migration path for stored links, exercised by
  `BundledGenerateDocumentActionConfigVersionTest`, for a change that otherwise needs none.
- The admin page's dangling-reference detection reads `actionConfig.catalogId()` and
  `templateId()` and reports what is missing. In variable mode there is nothing to check, so it
  needs a branch either way — but under one action that branch is inside code that currently assumes
  a configured template.

## A third occurrence of the same seam (added after the options above)

Writing this surfaced something that reframes it. The retry form and the composer **already share
the form builder** — both call `FormioFormGenerator.generateForm(fields, data)` — and they run the
same four steps around it:

|               | Composer `prepare`                                        | `RetryFormService`                      |
| ------------- | --------------------------------------------------------- | --------------------------------------- |
| template from | the composer's offered set                                | the process link                        |
| data from     | baseline mapping + fragment, evaluated now                | the link's `dataMapping`, evaluated now |
| which fields  | `MissingFieldSelector` — only what the mapping left empty | **all** of `template.fields()`          |
| form          | `generateForm(fields, data)`                              | `generateForm(fields, data)`            |

Two differences, and both are parameters rather than logic: **where the configuration comes from**,
and **which fields to ask for**.

So "where does the configuration come from" is not a question about two actions. It appears three
times:

1. the generate action — the link, or a process variable;
2. the retry form — the link today, and a process variable is exactly what #154 asks for;
3. the composer's own prepare — the composer configuration.

That changes what is worth unifying. The choice in this ADR is about the _action list_; the
duplication that actually costs is the **configuration source**, which is a smaller thing to model
and is needed under either option. A `TemplateAndData` source with two implementations — read from
a process link, read from a document on a variable — would make #154 a second implementation rather
than a second service, whether the plugin ends up with one action or two.

The field-selection difference is worth noticing for the same reason: "ask for everything,
prefilled" is precisely what #164 wants the composer to be able to do, and `RetryFormService`
already does it. One selection policy with two settings would deliver #164 as well.

## What would decide it

Not a principle — a measurement and a product question.

1. **How much of the 893-line configurator is genuinely shared?** Filename, correlation id, output
   format and result variable are common to both; catalog, template, variant and mapping are not. If
   the common part is small, Option B's single form is mostly a switch between two forms, and
   Option A is honest about that.
2. **Is retry wanted for dynamic documents (#154)?** If yes, Option B likely makes it cheaper, and
   that is the one place where the choice changes future work rather than present tidiness. If #154
   stays deferred indefinitely, this argument is hypothetical.
3. **Who picks the action?** If authors configuring process links are the same people who configure
   composers, two actions are a small vocabulary. If the dynamic path is mostly used by integrators
   setting a variable from code, they never see the picker at all and the title matters less than
   the documented contract (`ComposedLetter.dynamicDocument`).

## Decision

**Option A — two actions — with the configuration-source refactor taken on its own, first.**

The configurator evidence against merging is concrete and unchanged: 893 lines against 117, with
almost all of it hidden in variable mode. What the third-occurrence finding changes is that the
expensive duplication is not the action at all. Modelling "where template and data come from" once
pays under either option, makes #154 a second implementation rather than a second service, and is
the work that would have to happen first even if the actions were merged.

Done in that order, the action question becomes small and can be answered later on its own merits —
by which time there will be evidence from #154 about whether two sources really do read the same in
practice.

The bias named above is left standing deliberately: this was decided by the people who would also
have paid for the merge, and the evidence that carried it (893 lines against 117) is the kind that
is easy to measure and easy to over-weight. If the two sources turn out to read alike once #154 is
built, revisiting this is cheap for as long as the two configurators stay separate.

This recommendation is weakly held, and the bias is worth naming: it was written immediately after
building and naming the second action, which is not a neutral vantage point. The honest tie-breaker
is question 2 — if retry for dynamic documents is wanted, Option B gets materially stronger.

## Consequences

If A: the second action stays, `GENERATING_ACTION_KEYS` remains the seam, and #154 is built as a
second retry path or declined.

If B: `generate-document` gains a `source` property with a config-version bump, the dynamic
configurator becomes its variable-mode branch, the unreleased action is deleted, and
`RetryFormService` gets one branch rather than one new service. All of it frontend plus a small
backend change, and all of it cheaper now than after the next release.

Either way: the configuration-source refactor stands on its own, and #154 and #164 both get cheaper
for it. Note that B stops being free once the dynamic action ships — so if the merge is wanted at
all, it is wanted before the next release, while A remains reversible for as long as the two
configurators stay separate.
