# ADR 0008 — Rich-text values: whose format, and who translates

- **Status:** Proposed
- **Date:** 2026-10-12
- **Deciders:** Epistola plugin maintainers
- **Related:** ADR 0006 (letter composer configuration), `JsonSchemaMappingAnalyzer`, `TemplateField.FieldHints`, `FormioFormGenerator`, `MissingFieldSelector`, `LetterComposerService`, `ComposerWriteBackService`, `JsonataMappingService`, `docs/letter-composer.md`

## Context and problem statement

An Epistola template can declare a rich-text input. In its data contract such a field is a JSON
Schema `$ref` to one of two canonical URLs:

| `$ref`                                                 | Allows                                                      |
| ------------------------------------------------------ | ----------------------------------------------------------- |
| `https://epistola.app/schemas/richtext-inline-v1.json` | one paragraph: text and hard breaks                         |
| `https://epistola.app/schemas/richtext-block-v1.json`  | paragraphs, bullet and ordered lists (nestable), list items |

Both allow the marks `strong`, `em`, `underline`, `strike`, `subscript`, `superscript`, `link`
(`href`, `target`, `rel`, `title`) and `textStyle` (a hex `color`). The schemas ship in the
`epistola-catalog` jar (contract 1.4.0, `META-INF/epistola-catalog/schemas/`) and in the public
`@epistola.app/epistola-catalog` npm package. The contract keeps these `$ref`s un-inlined when it
serves a template's schema ("Rich-text `$ref`s keep their canonical URL"), so the URL is a reliable
marker.

The value Epistola accepts is a **subset of ProseMirror JSON**, `{"type":"doc","content":[…]}`, with
`additionalProperties: false` throughout. Epistola converts nothing. An HTML or markdown string fails
`JsonSchemaValidator` on generation and preview. Anything that slips past validation is rendered as
nothing by `RichTextVariableRenderer`, with no error.

This plugin cannot fill such a field from what a person types today:

- `JsonSchemaMappingAnalyzer` treats the external `$ref` as a complex scalar.
- The composer refuses it when it is required and unmapped (`UNSUPPORTED_FIELD`, 422), and
  `FormioFormGenerator` has nothing to render for it.
- `docs/letter-composer.md` documents the gap.

A baseline mapping can only fill the field when the case already holds a ProseMirror doc, and in
practice no case does.

**The case's format is not ours to choose.** The obvious framing is "which editor do we put in the
form", but that is the second question, not the first. A GZAC installation decides how it keeps text
on a case:

- **HTML**, because the Form.io `textarea` has a WYSIWYG mode (CKEditor by default, Quill optional)
  and that is what it produces. Case documents filled by ordinary Valtimo forms already hold HTML today.
- **Markdown**, because it is readable as stored, diffable, and portable to other systems such as
  ZGW, e-mail and APIs.
- **Plain text**, from a plain textarea, by far the most common.
- **ProseMirror**, if an installation wants exactly what Epistola renders.
- **Something else**, such as Quill Delta, or a format another system writes into the case.

Different case definitions in one installation can choose differently, and so can different fields
in one case definition.

So the plugin sits between two formats: **the case's format**, which the installation chooses, and
**Epistola's format**, which is fixed. Its job is to translate between them, and it must not impose
either one on the other side.

## Decision drivers

- **Flexible about the case's format.** Supporting HTML, markdown, plain text and ProseMirror must not
  be four special cases. A format nobody anticipated should be addable by an installation without
  forking.
- **Always valid towards Epistola.** Whatever arrives must leave as a doc that validates against
  `richtext-*-v1.json`. Losing formatting is acceptable, but a rejected letter or a silently empty
  paragraph is not.
- **Write-back must not change the case's format.** A composer that writes an edited value back
  (ADR 0006) must write it in the format the case keeps. A markdown field that comes back as
  ProseMirror JSON breaks every other consumer of that field.
- **Explicit beats guessed.** A string such as `2 * 3 * 4` is plain text to one installation and
  markdown to another. Getting it wrong quietly changes a letter about a person.
- **It should feel like a Valtimo form** where an editor is shown.
- **Stay within what can be shipped.** Mind the CDN and CSP for editor libraries, Valtimo and Angular
  version ranges, and coupling to Epistola's release cycle.
- **No personal data in logs.** A converter sees the letter's text. It may log counts of dropped
  elements, never content. See "Never log personal data" in `CLAUDE.md`.

## The questions, and the options for each

There are four questions. They are **independent**, and keeping them apart is most of this ADR's value.

### 1. How does a value get from the case's format to Epistola's?

**1a. A converter per format, behind one interface.** This is where the flexibility lives.

```java
public interface RichTextFormat {
    String id();                                                // "html", "markdown", "plain", "prosemirror"
    Map<String, Object> toEpistola(Object value, RichTextKind kind); // kind = INLINE | BLOCK
    Object fromEpistola(Map<String, Object> doc);               // optional: only needed for write-back
}
```

The plugin ships `prosemirror` (pass-through, still validated), `plain`, `html` (a jsoup allowlist)
and `markdown` (commonmark-java plus the strikethrough extension). Each is a Spring bean, so an
installation adds, say, `quill-delta` by declaring one more bean. Every implementation must produce
a doc that validates against the target schema. Anything it cannot express is unwrapped to its text
or dropped, never passed through. For `INLINE`, block structure collapses into one paragraph,
joined with hard breaks.

The formats differ in how much survives:

| Format      | Survives                                                             | Lost                                                      |
| ----------- | -------------------------------------------------------------------- | --------------------------------------------------------- |
| ProseMirror | everything in the subset                                             | —                                                         |
| HTML        | everything in the subset (`<u>`, `<sub>`, `<sup>`, `color` included) | headings → paragraphs, tables, images, other styles       |
| Markdown    | paragraphs, lists, bold, italic, strike (GFM), links                 | underline, sub/superscript, colour; headings → paragraphs |
| Plain text  | paragraphs (blank line) and hard breaks (newline)                    | all marks                                                 |

**1b. Epistola converts.** Epistola would accept HTML or markdown and convert with its own schema. One
implementation would serve every client, and the format would follow its own schema by construction.
But the change is out of this repository's hands and turns several source formats into contract
surface. It does not remove 1a's need for a seam, because the plugin would still have to say which
format it is sending. It is worth raising upstream regardless.

### 2. How does the plugin know which format a value is in?

**2a. The mapping says so, with a function per format.** For example
`{"toelichting": $fromMarkdown($doc.besluit.toelichting)}`, alongside `$fromHtml`, `$fromPlain` and
`$fromProseMirror`. This is explicit and visible where the value is chosen. It composes with any
expression, and it is consistent with how mappings already work. The cost is that it is easy to
forget, and a forgotten call means the raw string goes to Epistola and fails validation. The mapping
builder can soften that by suggesting the function when the target is a rich-text field.

**2b. The format is configured per source.** One setting on the plugin configuration, overridable per
process link and per composer (`richTextFormat: "markdown"`). Mappings stay as they are. But one
setting cannot express a case that keeps HTML in one field and markdown in another, and that is
exactly the flexibility the decision drivers ask for.

**2c. Detection.** A Map with `type: "doc"` is ProseMirror, a string starting with `<` is HTML, and
everything else is markdown or plain. It needs zero configuration. It is safe for one distinction
only: a ProseMirror object against a string. HTML against text is a guess, and markdown against
plain text is undecidable (`2 * 3 * 4`). Guessing fails the "explicit beats guessed" driver.

These combine. A plausible shape: **detect only the unambiguous case (an object is ProseMirror), use
2a when the mapping is explicit, and otherwise fall back to 2b's configured default for strings.**
Under this shape the configured default keeps simple installations simple, and a field in a
different format names its function.

### 3. Which editor does a person get?

The editor should **emit the case's format**, so what the composer asks for and what it writes back are
the same thing, and an existing value can be loaded for editing without conversion. That makes the
editor a consequence of question 2, not an independent choice:

| Case format | Editor                                                                                    | Notes                                                                                                                                                                                                      |
| ----------- | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| HTML        | Form.io `textarea` with `editor: ckeditor` or `quill`, toolbar limited to the subset      | Native to Valtimo, no frontend code. Loaded at runtime from `cdn.form.io` by default; we found no override in `@valtimo/*` 13.48. Quill 2 is in our lockfile via `formiojs`, so it could be served locally |
| Markdown    | Form.io `textarea`, optionally with a preview; or a markdown WYSIWYG                      | Plain textarea needs nothing; a WYSIWYG adds a dependency                                                                                                                                                  |
| Plain text  | Form.io `textarea`                                                                        | Trivial                                                                                                                                                                                                    |
| ProseMirror | Epistola's `<epistola-rich-text-input mode=inline\|block>`, as a Form.io custom component | Identical to Epistola's own editor, lossless. `@epistola/editor` is `private` and unpublished today, so this needs an Epistola-side package first                                                          |

A limited toolbar matters for HTML. Content the converter would drop is better never created than
silently lost.

A plugin-owned ProseMirror or TipTap editor was considered and rejected. It would be a second
implementation of Epistola's schema to keep in step. Embedding Epistola's own element gives the same
result without that.

### 4. What is stored and written back?

What the employee typed, in the **case's format**, on `inputs` and on any write-back destination.
What was rendered, in **Epistola's format**, on `data`, the snapshot ADR 0006 keeps so a retry
re-renders exactly. Conversion happens once, while composing, so the preview and the letter see the
same doc.

`fromEpistola` (ProseMirror → case format) is therefore only needed if an editor emits a format other
than the case's. Following question 3, that should not happen, so the reverse direction can stay
optional per format rather than a requirement on every converter.

## Shared by every option

These follow from the problem, whichever option wins:

- `JsonSchemaMappingAnalyzer` recognises the two URLs and records a `RichTextKind` (`INLINE` or
  `BLOCK`) in `FieldHints`. The field becomes a single mappable value instead of a complex scalar.
- `MissingFieldSelector` makes it askable, so the 422 goes away for these fields.
- One normalisation seam runs after the JSONata mapping and before anything reaches Epistola. It
  covers generation, the composer, preview and retry, and it walks nested objects and arrays to find
  rich-text fields.
- Converter output is checked against the bundled `richtext-*-v1.json` in tests, per format, so the
  "always valid" driver is enforced rather than hoped for.

## What would decide it

1. **What do installations actually keep on cases today?** If it is overwhelmingly HTML from Form.io
   WYSIWYG fields and plain text, then `html` + `plain` with a configured default (2b) covers almost
   everyone, and the rest of the interface waits. If markdown or mixed formats show up, 2a's
   functions are needed from the start.
2. **Can installations load an editor from `cdn.form.io`?** If a common CSP forbids it, the HTML
   editor must be Quill served locally, or the markdown/plain textarea, and that shifts the default.
3. **Will Epistola publish its rich-text input element, or accept other formats itself (1b)?** The
   first makes the ProseMirror row of question 3 cheap. The second would shrink this plugin's part to
   labelling the format.

## Leaning (not a decision)

- **1a**, the converter interface. It ships `prosemirror`, `plain`, `html` and `markdown`, and an
  installation can add its own.
- **Format resolution as combined above**: detect an object as ProseMirror; otherwise use a
  `$fromX` function where the mapping names one, then the configured default.
- **Editor emits the case's format** (question 3), starting with the Form.io `textarea` for HTML,
  markdown and plain text. The ProseMirror editor follows when Epistola publishes its element.
- Raise **1b** with Epistola in parallel. It does not block anything here.

## Consequences

If the leaning is accepted:

- **Formatting is lost.** A rich-text value loses whatever its case format can express and Epistola's
  subset cannot. This is documented per format, in the table above.
- **A new plugin setting.** The plugin configuration gains a default rich-text format. Its default
  needs choosing: `plain` is the safe one, because it never invents formatting.
- **Four new JSONata functions.** These are `$fromHtml`, `$fromMarkdown`, `$fromPlain` and
  `$fromProseMirror`, and the mapping builder's function list grows to match.
- **New dependencies:** jsoup and commonmark-java on the backend. The default frontend path adds none.
- **Shown, not logged.** A converter that drops content says so through counts, never content. Where
  a person should know, for example "the table in this field cannot be rendered", that message is
  shown to them, not logged.
- `docs/letter-composer.md` drops "Rich text is not supported" and describes formats instead.
