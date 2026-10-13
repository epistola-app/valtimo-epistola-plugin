# Rich text

Epistola templates can take **rich text**: formatted text with paragraphs, lists, bold, links and
colour. In a template's data contract a rich-text field is a `$ref` to one of two schemas, and its
value is a ProseMirror document, never a string:

| `$ref`                                                 | Kind   | Allows                                           |
| ------------------------------------------------------ | ------ | ------------------------------------------------ |
| `https://epistola.app/schemas/richtext-inline-v1.json` | inline | one paragraph: text and line breaks              |
| `https://epistola.app/schemas/richtext-block-v1.json`  | block  | paragraphs and (nested) bullet and ordered lists |

Both allow bold, italic, underline, strikethrough, sub- and superscript, links (`http`, `https`,
`mailto`, `tel`) and text colour (`#rgb` / `#rrggbb`).

A case rarely keeps text as ProseMirror. A Form.io WYSIWYG field writes HTML, a plain text area
writes text, and another system may write markdown. So the plugin **translates**: the case keeps
its own format, and the plugin converts to Epistola's document on the way out. Why it works this
way, and not inside Epistola, is [ADR 0008](adr/0008-rich-text-inputs.md).

## Which format a value is in

The plugin never guesses this from the text. `2 * 3 * 4` is arithmetic in one installation and
emphasis in another. Whoever knows the format says it:

- **A data mapping names it.** The author knows what the case keeps, so the mapping says so:
  `$richText($doc.x, "html")`. `$richText(value)` without a format uses the default. A string
  mapped onto a rich-text field _without_ `$richText` is sent as it is, and Epistola refuses it
  ("string found, object expected"). The mapping builder warns about such a field.
- **The plugin's own editors say it.** The letter composer and the retry form submit what was
  typed into a rich-text editor as a _tagged value_ that names the editor's format,
  `{"$richText": {"format": "html", "kind": "BLOCK", "value": "<p>…</p>"}}`, and the plugin
  converts it when the data is sent. See [Where it happens](#where-it-happens). A composer's
  baseline mapping is a mapping like any other: a rich-text field it fills needs `$richText`.

A value that already is a document, an object shaped `{"type": "doc", …}`, needs no format. Through
`$richText` it is narrowed to what the schema allows; mapped as it is, it goes as it is, and
Epistola validates it.

`$richText` gives a block document. An **inline** field accepts it while it holds one paragraph; to
narrow anything longer to one, name the kind: `$richText($doc.aanhef, "html", "inline")`.

A field that takes a **list** of rich text is mapped item by item, the JSONata way, inside an array
constructor:

```jsonata
{ "alineas": [$doc.alineas.$richText($, "html")] }
```

The `[…]` matters: JSONata hands over a lone value instead of a list when the case list has
exactly one item, and a list field refuses a single value. An editor that takes a list tags each
item, and an item left empty is left out.

```yaml
epistola:
  rich-text:
    default-format: html # html (default) | plain | markdown | prosemirror | your own
```

The default decides two things: which editor the composer and the retry form show (and so the
format of what they write back), and how `$richText(value)` without a format reads `value`. `html`
is the default because it is what Valtimo's WYSIWYG text areas write. A value from an ordinary
**plain text area** is named, `$richText($doc.x, "plain")`, because read as HTML its line breaks
collapse into spaces and a `<` followed by a letter starts a tag.

**The format is configuration.** Changing the default changes what the editors write and what
`$richText(value)` reads, so treat it like a schema change. What an editor already submitted keeps
the format it was typed in: the tag says it. It is
meant to become selectable per form or component later; until then it is one setting for the
installation.

## The formats

| Format        | Reads                                                     | Kept                                                                             | Lost                                                                                           |
| ------------- | --------------------------------------------------------- | -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `plain`       | text; a blank line separates paragraphs                   | paragraphs, line breaks                                                          | — (there is no formatting to lose)                                                             |
| `html`        | what Quill (1 and 2) and CKEditor write, and similar HTML | everything the schema allows, as elements or as inline `style`                   | headings and table cells (become paragraphs), images, scripts, other styles                    |
| `markdown`    | CommonMark, plus GFM strikethrough                        | paragraphs, hard breaks, nested lists, `**strong**`, `*em*`, `~~strike~~`, links | underline, sub/superscript and colour (no syntax); headings, quotes and code become paragraphs |
| `prosemirror` | a document, as an object or JSON text                     | everything the schema allows                                                     | nodes and marks outside the schema                                                             |

Whatever a format produces is checked against Epistola's schema, and whatever the schema does not
allow is narrowed rather than sent:

- an unknown mark is dropped;
- a link that would run something (`javascript:`) or points nowhere (a relative URL) loses its link
  but keeps its text;
- an **inline** field gets exactly one paragraph, with list items kept as `• ` / `1. ` lines.

Blank input is removed rather than sent as an empty document, so a required field cannot pass
empty. That includes markup around nothing, like the `<p><br></p>` an emptied editor leaves.

## In your own forms

Rich text does not need the letter composer. Any Valtimo form can collect it with Form.io's own
WYSIWYG text area, which writes HTML into the case document or a process variable. A
generate-document mapping, or the preview component, then hands that to the rich-text field:

```jsonata
{ "toelichting": $richText($doc.besluit.toelichting, "html") }
```

`$richText($doc.besluit.toelichting)` does the same with the default format (`html`). A bare
`$doc.besluit.toelichting` does not: it sends the HTML as text, which Epistola refuses.

What the form builder offers by default is more than Epistola renders: headings, tables, images,
media. The conversion drops those, keeping the text. To offer only what survives, give the text
area this editor configuration (in the builder's JSON for the component):

```json
{
  "type": "textarea",
  "key": "besluit.toelichting",
  "editor": "ckeditor",
  "isUploadEnabled": false,
  "wysiwyg": {
    "toolbar": [
      "bold",
      "italic",
      "|",
      "fontColor",
      "|",
      "bulletedList",
      "numberedList",
      "outdent",
      "indent",
      "|",
      "link",
      "|",
      "undo",
      "redo"
    ],
    "removePlugins": [
      "Heading",
      "BlockQuote",
      "Table",
      "TableToolbar",
      "Image",
      "ImageCaption",
      "ImageStyle",
      "ImageToolbar",
      "ImageUpload",
      "ImageResize",
      "EasyImage",
      "CKFinder",
      "CKFinderUploadAdapter",
      "MediaEmbed",
      "Alignment",
      "IndentBlock",
      "FontFamily",
      "FontSize",
      "FontBackgroundColor"
    ]
  }
}
```

**Both keys matter.** The toolbar only hides buttons: a loaded plugin still accepts its content from
a paste, and CKEditor's autoformat turns `# ` into a heading and `> ` into a quote.
`removePlugins` takes those features out of the editor, so a paste arrives as paragraphs and
`# ` stays text, while `- ` still starts a list. `Font` stays, because it carries the colour picker;
the background colour it also accepts is dropped on conversion. CKEditor ignores a name its build
lacks.

Leave out the list buttons for a field that takes inline rich text, and add `"multiple": true` for
one that takes a list.

### Lists without bullets in Valtimo

In a Valtimo form, a list typed in CKEditor showed as indented text without bullets or numbers,
including in Valtimo's own WYSIWYG text areas. The letter was unaffected; only the editor's display
was. Valtimo 13.48 does not handle it, which is easy to doubt, so here is what was checked:

- **The cause is Carbon's reset.** Valtimo loads Carbon globally
  (`@valtimo/components/assets/css/carbon.scss`, which leaves `$css--reset` on), and its reset sets
  `ol, ul { list-style: none }` and zeroes their padding. CKEditor draws lists with the browser's
  default markers, and Form.io's own CSS adds only `.formio-component-textarea .ck.ck-editor ul
{ margin-left: 10px }`, because it assumes Bootstrap, which keeps the markers.
- **Valtimo restores markers in one place only:** its text widget, `.valtimo-widget-text__content
ul { list-style: disc }` and so on, in `@valtimo/layout`. No rule in any published `@valtimo`
  13.48 package, compiled components and `formio-carbon.scss` included, does so for CKEditor.
- **In the running app** the editor's `ul` computed `list-style-type: none`, and the only rules
  matching it were that reset and Form.io's margin. The same Form.io and CKEditor build on a page
  without Valtimo's CSS shows bullets and nested circles.

The plugin therefore restores them itself: at startup it adds a style element
(`epistola-ckeditor-list-styles`, from `utils/ckeditor-list-styles.ts`) with Valtimo's text-widget
rules scoped to `.ck-content`, CKEditor's content area, editable or read-only. Nothing outside an
editor changes. It applies to every CKEditor in the host, Valtimo's own included, since they have the
same defect, and it is skipped when the plugin is disabled (`EPISTOLA_ENABLED=false`). Remove it once
Valtimo restores the markers itself.

### In the demo

The test-app uses both paths on `besluit-bezwaar`, whose `motivation` is block rich text:

- **Bezwaar** (`objection`): the _Beoordeel Bezwaar_ form asks for `pv:motivation` with the
  configuration above, and `generate-decision` maps it with `$richText($pv.motivation, "html")`.
- **Correspondentie**: the composer asks for it with the same editor and writes it back to
  `besluit.toelichting`. The case shows that field in a read-only `formio` widget
  (`besluit-toelichting.form.json`), since a `fields` widget has no display type for HTML and
  would print the tags.

## In the letter composer and the retry form

When the composer or a retry form asks for a rich-text field, it generates the editor itself,
matching the default format, so what a person types is already in the case's format:

- `html` gives the CKEditor text area above, with the same toolbar and removed plugins.
- `plain` and `markdown` give a plain text area.

A field that takes a **list** of rich text gets one editor per item, with "add another".

The HTML editor is CKEditor rather than Quill out of necessity, not preference. Form.io 4
deep-merges a component's Quill settings into its own default toolbar, index by index, so a shorter
toolbar can add buttons but never remove headings, fonts, images or video. CKEditor has no default
toolbar to merge into. Form.io's CKEditor build has no underline, strikethrough or sub- and
superscript buttons. Those marks still convert when they arrive in HTML from elsewhere, Quill
included.

The editor itself is Form.io's, not the plugin's. Form.io loads it from its CDN (`cdn.form.io`)
exactly as it does for any WYSIWYG text area an author builds in Valtimo, and neither the plugin nor
Valtimo overrides `Formio.cdn`. An installation where WYSIWYG fields already work needs nothing more.

Each generated editor carries the format it writes and the field's kind (`epistolaRichText` on the
Form.io component). The composer and the retry form read that to tag what was typed, and the
composer's write-back gives the case the typed text itself, not the tag, so the case's own forms
can show it.

A value that is already a document, such as a retry form prefilled with what was rendered, is
written back out in the default format, so the editor shows text rather than JSON. A value tagged
earlier, a retry form's previous correction, is shown as it was typed.

## When something is lost

Whatever does not survive is reported in the log, by kind and count, never by its text:

```
WARN  Rich text for field 'besluit.toelichting' of template 'besluit' lost what Epistola cannot render: heading ×1, table ×1
```

It is a **warning when a letter is generated**: the form offers more than the letter shows, and
narrowing the editor (see [In your own forms](#in-your-own-forms)) fixes it. For a **preview** it is
logged at `DEBUG`, because the composer previews on every change and would otherwise repeat the
same warning many times. `$richText` reports the same way, naming the format it read.

The kinds a report can name: `heading`, `table`, `image`, `media`, `block quote`, `code block`,
`code`, `horizontal rule`, `alignment`, `font`, `background colour`, `indentation`, `colour` (a
notation the schema cannot take), `link` (one that would run something or points nowhere),
`script or style`, `embedded content`, `form control`, `list style`, `paragraphs or lists in an
inline field`, and the name of any other node or mark a ProseMirror document carries that the
schema lacks.

A format of an installation's own reports what it skips by overriding
`toProseMirror(value, RichTextLosses)`. What the sanitizer narrows afterwards is reported
regardless.

## Where it happens

- **A mapping** converts in `$richText`, while it is evaluated. Nothing converts mapped data later.
- **What a person typed into the plugin's editors** is tagged with its format in the browser
  (`tagRichText`), and the Epistola client turns every tag into a document as it builds the
  generate and preview requests (`RichTextValues`, called from `EpistolaServiceImpl`). Data
  without tags passes unchanged. Every path to Epistola goes through those two requests, so a
  preview and its letter convert the same way, and a new path needs nothing extra.

Converting a tag needs nothing but the tag: no template contract is read, and no Epistola
connection details are needed for it. A tag whose format the installation does not know, or whose
format fails on the value, stops the request with a message naming the field and the format, never
the text: beside the preview in the composer, on the result variable for a generate task.

## Adding a format

Declare a Spring bean implementing `app.epistola.valtimo.richtext.RichTextFormat`:

```java
@Bean
RichTextFormat quillDeltaFormat() {
    return new RichTextFormat() {
        public String id() { return "quill-delta"; }

        public Map<String, Object> toProseMirror(Object value, RichTextLosses losses) {
            // turn the value into {"type": "doc", "content": [...]};
            // record what it cannot keep with losses.lost("…") — kinds, never content
        }
    };
}
```

Its id then works as `$richText(value, "quill-delta")` and as the default format. A format does not
have to produce a valid document, because the result is narrowed afterwards. It should keep as
much of the meaning as the schema can carry. `fromProseMirror`, optional, writes a document back in
the format, to show one in an editor.

How a person types the format is a separate extension point, `RichTextEditor`: a bean naming the
format it writes and the Form.io component for it. A format without one gets a plain text area;
`html` has `CkEditorHtmlEditor`.

**Never log the value.** It is the case's own text, and so personal data.

## Not (yet) supported

- **Headings.** Epistola's rich-text schemas do not allow them yet ("reserved for future versions"
  in `richtext-block-v1.json`), and a document containing one would be refused as a whole. A
  heading therefore keeps its text as a plain paragraph. When Epistola publishes a schema version
  with headings, recognising its URL and letting `heading` nodes through is a small change in
  `ProseMirrorSanitizer`, plus heading buttons in the editor's toolbar.
- A rich-text schema **inlined** into a contract, rather than referenced by its URL, is not
  recognised. The contract keeps these references as URLs, so this does not occur with Epistola's
  own schemas.
