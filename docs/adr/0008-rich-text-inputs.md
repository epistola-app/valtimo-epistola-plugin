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

**1b. Epistola converts — rejected.** Epistola would accept HTML or markdown alongside ProseMirror
and convert them itself. One implementation would serve every client. We reject it anyway:

- **It weakens the contract.** One strictly validated, versioned format is a contract. Several input
  formats each become contract surface, and every one of their edge cases becomes a compatibility
  question across server versions, such as what a server does with a `<table>`.
- **It can never be complete.** Customers keep their own formats, and some of them are
  vendor-specific. Epistola would support HTML and markdown, then be asked for Quill Delta, then for
  the next one. The knowledge of what a format means sits with the customer, so the extension point
  must sit on the client side, where they can reach it.
- **It is the right duplication.** Each client converts what _its_ ecosystem produces. This plugin's
  HTML is Form.io CKEditor or Quill output, which a client for another platform never sees.

The part that is worth sharing across clients is validity. Epistola already publishes the schemas. A
**conformance corpus** of valid and invalid rich-text documents, published next to them, would let
every client's converters test against the same cases without sharing code. That is a suggestion to
Epistola, and nothing here waits on it.

### 2. How does the plugin know which format a value is in?

**2a. The mapping says so, with one function that takes the format's id.** For example
`{"toelichting": $richText($doc.besluit.toelichting, "markdown")}`. There is deliberately one
function, not one per format (`$fromMarkdown`, …). A format an installation registers is then usable
by its id without the plugin knowing it exists.

- **For:** it is explicit and visible where the value is chosen. It composes with any expression, and
  it is consistent with how mappings already work.
- **Against:** it is easy to forget, and a forgotten call means the raw string goes to Epistola and
  fails validation. The mapping builder can soften that by suggesting the function when the target
  is a rich-text field.

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

| Case format | Editor                                                                                                  | Notes                                                                                                                                                                                                      |
| ----------- | ------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| HTML        | Form.io `textarea` with `editor: ckeditor` or `quill`, toolbar limited to the subset                    | Native to Valtimo, no frontend code. Loaded at runtime from `cdn.form.io` by default; we found no override in `@valtimo/*` 13.48. Quill 2 is in our lockfile via `formiojs`, so it could be served locally |
| Markdown    | Form.io `textarea`, optionally with a preview; or a markdown WYSIWYG                                    | Plain textarea needs nothing; a WYSIWYG adds a dependency                                                                                                                                                  |
| Plain text  | Form.io `textarea`                                                                                      | Trivial                                                                                                                                                                                                    |
| Own format  | The installation's own Form.io component, picked through the per-input presentation override (ADR 0006) | The plugin cannot know the editor for a format it does not know; it only needs the component to emit that format                                                                                           |

A limited toolbar matters for HTML. Content the converter would drop is better never created than
silently lost.

**ProseMirror is deliberately missing from the table.** Epistola's editor
(`<epistola-rich-text-input>`) is private today and may be published one day. Even then it helps
only an installation that stores ProseMirror on the case, and that is the least likely choice:
nothing else in a GZAC installation can read it. Two situations would make it worth adopting:

- an installation that does store ProseMirror; or
- an installation that stores HTML but wants Epistola's editing experience. That needs the reverse
  conversion (`fromEpistola`, see question 4).

Neither is a reason to wait. Conversion is the main path, not a stopgap until an editor arrives.

A plugin-owned ProseMirror or TipTap editor was considered and rejected. It would be a second
implementation of Epistola's schema to keep in step, for the same small audience.

### 4. What is stored and written back?

What the employee typed, in the **case's format**, on `inputs` and on any write-back destination.
What was rendered, in **Epistola's format**, on `data`, the snapshot ADR 0006 keeps so a retry
re-renders exactly. Conversion happens once, while composing, so the preview and the letter see the
same doc.

`fromEpistola` (ProseMirror → case format) is therefore only needed if an editor emits a format other
than the case's. Following question 3, that should not happen, so the reverse direction stays
optional per format rather than a requirement on every converter. Its one foreseeable use is
Epistola's editor in front of an HTML case.

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

## Recommendation

1. **Epistola keeps one format.** All conversion happens in the client, behind a `RichTextFormat`
   extension point (1a). Rejecting 1b is the one choice in this ADR that is firm, not a leaning.
2. **The plugin ships four formats:** `plain`, `html`, `markdown` and `prosemirror`. An installation
   adds its own by registering one more bean. Each converter is tested against Epistola's schema.
3. **The format of a value is resolved explicitly, in this order:**
   1. an object shaped `{"type":"doc", …}` is ProseMirror;
   2. otherwise `$richText(value, "<format>")` in the mapping decides;
   3. otherwise the configured default applies, `plain` unless an installation says differently.

   Nothing guesses between kinds of strings.

4. **The editor emits the case's format.** The plugin generates it for its own formats:
   - the Form.io WYSIWYG `textarea` for HTML, with its toolbar limited to Epistola's subset;
   - a plain `textarea` for markdown and plain text.

   An installation's own format brings its own component.

5. **The case keeps its format.** Typed inputs and write-back are stored in the case's format, and
   only the rendered snapshot is ProseMirror. The conversion runs once, while composing, so the
   preview and the letter use the same document.
6. **Suggest a conformance corpus to Epistola.** It is optional, and nothing here waits on it.

**Order of work:** marker detection, the conversion seam and `plain` come first. On their own they
lift the 422 and are useful. Then `html` with the WYSIWYG editor, which is where Valtimo case data
already is. Then `markdown`.

## Consequences

If the recommendation is accepted:

- **Formatting is lost.** A rich-text value loses whatever its case format can express and Epistola's
  subset cannot. This is documented per format, in the table above.
- **A new plugin setting.** The plugin configuration gains a default rich-text format. Its default
  needs choosing: `plain` is the safe one, because it never invents formatting.
- **One new JSONata function**, `$richText(value, format)`. The mapping builder lists the
  registered formats as its second argument.
- **A converter is a public extension point.** Its interface is supported API from then on, so it
  needs to be versioned with the same care as the plugin's other public surface.
- **New dependencies:** jsoup and commonmark-java on the backend. The default frontend path adds none.
- **Shown, not logged.** A converter that drops content says so through counts, never content. Where
  a person should know, for example "the table in this field cannot be rendered", that message is
  shown to them, not logged.
- `docs/letter-composer.md` drops "Rich text is not supported" and describes formats instead.
