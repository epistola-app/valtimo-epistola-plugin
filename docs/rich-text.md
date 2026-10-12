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
emphasis in another. It decides in this order:

1. **A document is a document.** An object shaped `{"type": "doc", …}` is used as it is, after
   being narrowed to what the schema allows.
2. **The mapping says so.** `$richText(value, "markdown")` reads `value` in the named format.
   `$richText(value)` uses the default.
3. **Otherwise the default applies.** A string mapped straight onto a rich-text field is read in
   `epistola.rich-text.default-format`.

A field that takes a **list** of rich text is converted item by item, and empty items are left out.
`$richText` reads one value; to name the format of a list, map over it the JSONata way:

```jsonata
{ "alineas": $doc.alineas.$richText($, "html") }
```

JSONata hands over a lone value instead of a list when the case list has exactly one item. A list
field takes such a value as a list of one, so that needs no `[…]` around the expression.

```yaml
epistola:
  rich-text:
    default-format: html # plain (default) | html | markdown | prosemirror | your own
```

`plain` is the default because it is the one format that never invents formatting.

## The formats

| Format        | Reads                                                     | Kept                                                                             | Lost                                                                                           |
| ------------- | --------------------------------------------------------- | -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `plain`       | text; a blank line separates paragraphs                   | paragraphs, line breaks                                                          | — (there is no formatting to lose)                                                             |
| `html`        | what Quill (1 and 2) and CKEditor write, and similar HTML | everything the schema allows, as elements or as inline `style`                   | headings and table cells (become paragraphs), images, scripts, other styles                    |
| `markdown`    | the common CommonMark subset                              | paragraphs, hard breaks, nested lists, `**strong**`, `*em*`, `~~strike~~`, links | underline, sub/superscript and colour (no syntax); headings, quotes and code become paragraphs |
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

With `default-format: html` the plain mapping `$doc.besluit.toelichting` does the same.

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
    ]
  }
}
```

Leave out the list buttons for a field that takes inline rich text, and add `"multiple": true` for
one that takes a list.

## In the letter composer and the retry form

When the composer or a retry form asks for a rich-text field, it generates the editor itself,
matching the default format, so what a person types is already in the case's format:

- `html` gives the CKEditor text area above.
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

A value that is already a document, such as a retry form prefilled with what was rendered, is
written back out in the default format, so the editor shows text rather than JSON.

## Where it happens

Conversion runs in `EpistolaServiceImpl`, just before a generation or preview request is sent.
Every path goes through it: the generate action, the preview component, the composer and the retry
form. The preview and the letter therefore cannot differ.

To know which fields are rich text, the service reads the template's contract first. If that read
fails, the data is sent as mapped, and Epistola's own validation reports what it cannot render.

## Adding a format

Declare a Spring bean implementing `app.epistola.valtimo.richtext.RichTextFormat`:

```java
@Bean
RichTextFormat quillDeltaFormat() {
    return new RichTextFormat() {
        public String id() { return "quill-delta"; }

        public Map<String, Object> toProseMirror(Object value) {
            // turn the value into {"type": "doc", "content": [...]}
        }
    };
}
```

Its id then works as `$richText(value, "quill-delta")` and as the default format. A format does not
have to produce a valid document, because the result is narrowed afterwards. It should keep as
much of the meaning as the schema can carry. Two optional methods complete it:

- `fromProseMirror` writes a document back in the format, used to show one in an editor;
- `editor` supplies the Form.io component a person types the format into.

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
