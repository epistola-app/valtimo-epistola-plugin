# ADR 0008 — Rich-text values: whose format, and who translates

- **Status:** Accepted — the recommendation, built as described under [As built](#as-built-2026-10-12), where to convert revised under [Revision](#revision-2026-10-13-convert-where-the-format-is-known)
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

| Case format | Editor                                                                                                  | Notes                                                                                                            |
| ----------- | ------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| HTML        | Form.io `textarea` with `editor: ckeditor` or `quill`, toolbar limited to the subset                    | Native to Valtimo, no frontend code. Form.io loads it from its own CDN, as for any WYSIWYG field in Valtimo      |
| Markdown    | Form.io `textarea`, optionally with a preview; or a markdown WYSIWYG                                    | Plain textarea needs nothing; a WYSIWYG adds a dependency                                                        |
| Plain text  | Form.io `textarea`                                                                                      | Trivial                                                                                                          |
| Own format  | The installation's own Form.io component, picked through the per-input presentation override (ADR 0006) | The plugin cannot know the editor for a format it does not know; it only needs the component to emit that format |

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
What is rendered is converted to **Epistola's format** on its way to Epistola, by the same code
for the preview and for the letter, so both see the same document. (Proposed here was converting
once while composing and keeping the document on `data`; see [As built](#as-built-2026-10-12) for
why it moved.)

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
2. **Do installations use Form.io's WYSIWYG editors today?** The editor is Form.io's, loaded the
   way Form.io loads it for any Valtimo WYSIWYG field, so where those work this does too.

## Recommendation

1. **Epistola keeps one format.** All conversion happens in the client, behind a `RichTextFormat`
   extension point (1a). Rejecting 1b is the one choice in this ADR that is firm, not a leaning.
2. **The plugin ships four formats:** `plain`, `html`, `markdown` and `prosemirror`. An installation
   adds its own by registering one more bean. Each converter is tested against Epistola's schema.
3. **The format of a value is resolved explicitly, in this order:**
   1. an object shaped `{"type":"doc", …}` is ProseMirror;
   2. otherwise `$richText(value, "<format>")` in the mapping decides;
   3. otherwise the configured default applies, `plain` unless an installation says differently.
      (Built with `html` as the default instead; see [As built](#as-built-2026-10-12).)

   Nothing guesses between kinds of strings.

4. **The editor emits the case's format.** The plugin generates it for its own formats:
   - the Form.io WYSIWYG `textarea` for HTML, with its toolbar limited to Epistola's subset;
   - a plain `textarea` for markdown and plain text.

   An installation's own format brings its own component.

5. **The case keeps its format.** Typed inputs and write-back are stored in the case's format.
   Conversion runs where data leaves for Epistola, the same way for the preview and the letter.
6. **Suggest a conformance corpus to Epistola.** It is optional, and nothing here waits on it.

**Order of work:** marker detection, the conversion seam and `plain` come first. On their own they
lift the 422 and are useful. Then `html` with the WYSIWYG editor, which is where Valtimo case data
already is. Then `markdown`.

## Consequences

- **Formatting is lost.** A rich-text value loses whatever its case format can express and Epistola's
  subset cannot. This is documented per format, in the table above.
- **A new setting**, `epistola.rich-text.default-format`, defaulting to `html` (see As built).
- **One new JSONata function**, `$richText(value, format)`. The mapping builder lists the
  registered formats as its second argument.
- **A converter is a public extension point.** Its interface is supported API from then on, so it
  needs to be versioned with the same care as the plugin's other public surface.
- **Two new dependencies**, jsoup and commonmark-java (with its strikethrough extension), see below.
- **Nothing about content is logged.** Converters log nothing; a failing one surfaces as a
  `RichTextConversionException` naming the field and the format, raised without its cause, whose
  message might quote the text.
- `docs/letter-composer.md` drops "Rich text is not supported" and describes formats instead.

## As built (2026-10-12)

The recommendation was built as written, with the differences below worth knowing, several of them
from an architecture review of the first version.

**Conversion happens where data leaves for Epistola, not while composing.** _Superseded by the
[revision](#revision-2026-10-13-convert-where-the-format-is-known): the decorator is gone._
`RichTextEpistolaService`, a decorator around the Epistola client, converts the data's rich-text
fields in `submitGenerationJob` and `previewDocument`, just before the request is sent. (A first
version did this inside `EpistolaServiceImpl`; review moved it out, so the client is only a client
and replacing it cannot silently drop the conversion.) Every path reaches Epistola through those two methods: the
generate action, the preview component, the composer and the retry form. Converting there, rather
than at each caller, is what guarantees the preview and the letter cannot convert differently. The
composer's `data` snapshot therefore keeps the case's format, which keeps it readable as stored.

The price is one extra call: which fields are rich text is only known from the template's contract,
so it is read before sending, and kept for a minute per template because the composer previews on
every change. When that read fails, the data goes as mapped. Epistola
validates it regardless and refuses what it cannot render with a message of its own, so the
fallback costs a clearer error at worst, never a wrong letter.

**The default format is a Spring property**, `epistola.rich-text.default-format`, not a plugin
configuration field. The format a case keeps text in is a property of the installation's forms,
not of an Epistola connection, and a property needs no configurator UI, translations or migration.

**It defaults to `html`, not `plain`.** Review found that the format is decided in two places:
per mapping (`$richText`) and globally for the generated editors and their round trip. With a
`plain` default, a retry form showed a formatted letter as plain text, and resubmitting it lost the
formatting. The decision: the format is **configuration**, meant to become selectable per form or
component later, and `html` is the default, which is what Valtimo's WYSIWYG text areas and the
generated editors write. With that, editor and conversion agree, and a rendered document
round-trips. Two consequences are accepted and documented:

- A plain text area's value needs `$richText(x, "plain")`, or its line breaks collapse.
- Changing the default reinterprets stored text, like a schema change.

**Formats and editors are separate extension points.** A first version put the Form.io editor on
`RichTextFormat`, which then had four reasons to change. `RichTextFormat` now parses and writes
back. `RichTextEditor` names a format and the component for it, with `CkEditorHtmlEditor` for HTML
and a plain text area for any format without one.

**Known limitation: the contract read is the template's current one.** Epistola renders the
version deployed to the environment. A newer, undeployed version that changes a field's rich-text
type makes the conversion follow the newer contract, and Epistola refuses the letter. The composer
and mapping builder read the contract the same way. The fix is reading field types per environment,
once the contract offers that; until then it is documented in `docs/rich-text.md`.

**Parsing uses jsoup and commonmark-java, as plain dependencies.** A first version carried its own
HTML tokenizer and markdown parser to avoid dependencies. They worked on what editors write, but
parsing is where hand-written code is weakest. Markdown has CommonMark's emphasis rules, setext
headings and reference links. Pasted HTML has HTML5's implicit closing and misnesting, and 2,231
named character references. Both libraries implement their specifications, are pure Java without
dependencies of their own, and replaced about 800 lines. What stays is plugin logic: walking the
parsed tree into a document, rebuilding Quill's flat lists (Quill 2's `<li data-list>` and older
Quill's `ql-indent-N`), mapping inline styles to marks, and recording losses.

They are **not shaded**. Shading in this build exists for a clash that happened:
`json-schema-validator`'s 1.x and 2.x share a package, and Valtimo failed to start. These have no
such clash. commonmark-java is not on Valtimo's classpath at all, and jsoup is there only through
Tika, which resolves to our version: a minor bump in a library that keeps its API stable. A shaded
copy would also hide them from the host's vulnerability scanning and patching. If jsoup ever does
clash, shading it then is a local change.

**The HTML editor is CKEditor, because Quill's toolbar cannot be narrowed.** Tried in a browser
against Form.io 4.19.5: a component's Quill settings are deep-merged into Form.io's default toolbar,
index by index, so a toolbar of eight buttons still showed headings, fonts, background colour,
alignment, images, video and formulas. All of those are content the conversion would drop.
CKEditor has no default toolbar to merge into, so its toolbar is exactly what the component lists.

A toolbar only hides buttons, though. A loaded plugin still accepts its content from a paste, and
autoformat turns `# ` into a heading. So the editor also removes the plugins for content Epistola
cannot render (`removePlugins`). In the browser, a pasted heading, table and image then arrived as
paragraphs, and `# ` stayed text. CKEditor ignores a removed name its build lacks.

Its Form.io build lacks underline, strikethrough and sub- or superscript, so the editor offers
bold, italic, colour, lists, indentation and links. Those four marks still convert when they arrive
in HTML from elsewhere. Its colour picker writes `hsl(…)`, which the HTML reader converts to the
hex the schema requires. The whole chain was then checked end to end: text typed into the
generated form, converted, and rendered by Epistola's preview.

**The reverse direction exists for the shipped formats.** `fromProseMirror` writes a document back
as text, HTML or markdown. It is used where a form shows a value that is already a document, such as
a retry form prefilled with what was rendered, so the editor shows text rather than JSON. It stays
optional on the interface.

Two smaller choices:

- **Blank values are removed, not converted.** A blank string, or markup around nothing (an emptied
  Quill editor leaves `<p><br></p>`), is dropped from the data rather than turned into an empty
  document. Otherwise a required field would pass empty.
- **Arrays of rich text are lists of documents.** Each item is converted on its own, empty items
  are left out, and the form asks for them with Form.io's `multiple` text area: one editor per item.
  `$richText` stays one value in, one document out; a list is mapped the JSONata way
  (`$doc.alineas.$richText($, "html")`). JSONata collapses a one-item list into its item, which
  would make the field fail only for cases with exactly one, so a list field takes a single value
  as a list of one.
- **What is lost is reported, by kind.** Readers and the sanitizer record what they flatten or
  drop in a `RichTextLosses` (kinds and counts, never text). A generated letter logs it at `WARN`
  with the field path and template; a preview logs it at `DEBUG`, since the composer previews on
  every change. This is what makes the accepted mismatch below observable.
- **Author-built forms are not restricted.** A CKEditor text area an author builds offers more than
  Epistola renders. The mismatch costs formatting, not correctness, because the sanitizer still
  narrows everything. It is visible in the preview and reported in the log. A palette component that
  enforces the editor configuration was considered and deferred: the gap narrows as Epistola's
  schema grows (headings first), so revisit it when a schema version with headings is published.
- **It is not composer-specific.** A regular Valtimo form with a WYSIWYG text area writes HTML to
  the case. A generate-document mapping then reads it with `$richText(…, "html")`, or by the
  default format, and the conversion step handles it like any other value. (Since the revision,
  only with `$richText`.)

## Revision (2026-10-13): convert where the format is known

The decorator answered "where is every path to Epistola covered?" rather than "who knows the
format?". It made conversion invisible (reading `PreviewService` gave no hint that its data was
converted), it repeated ten client methods only to pass them through, and it read the template's
contract before every generation or preview of a template not seen in the last minute, rich text or
not. Its implicit step, reading any string in a rich-text field in the default format, was a
configured guess, which this ADR's own rule ("the format is said, never guessed") argues against.

**Decision:** conversion happens where the format is known, and nowhere else.

- **A data mapping** is the author's statement of how case data becomes template data. The author
  knows the case's format, so the mapping says it: `$richText($doc.x, "html")`. Mapped data is not
  converted afterwards; a string mapped onto a rich-text field as it is goes to Epistola and is
  refused. The mapping builder marks such a field, so the author learns this while writing the
  mapping rather than from a failed letter.
- **The plugin's own editors**, the letter composer and the retry form, ask for a rich-text field
  with the default format's editor, so the plugin knows what they write. `EditorRichText` converts
  that data at the four places it leaves for Epistola: the composer's preview, the generation of a
  composed letter, a retry's generation (inside the submit, so a failure lands on the result
  variable like any other), and the retry form's preview (its overrides only). It keeps the
  decorator's field cache and loss reporting.

**Consequences:**

- **Behaviour change for mappings:** a bare HTML string mapped onto a rich-text field no longer
  converts. It did only in the unreleased version, so no released installation relies on it.
- **The default format** now decides the editors' format and `$richText(value)` without a format,
  and no longer reinterprets mapped strings.
- **A composed letter arrives as one object**, baseline mapping and typed input merged, so a text
  value in any of its rich-text fields is read in the editor's format. Separating the two would
  need `inputs` on the composer's preview request and on `DynamicDocument`, which is a change to
  stored shapes the composer's `schemaVersion` exists for; not done here.
- **The contract is read only when editor data is sent**, not for every letter.
