# Generate-document action versioning

The `epistola-generate-document` process-link action stores its configuration in
Valtimo's `actionProperties` JSON. `actionConfigVersion` defines how the complete
action object is interpreted; individual fields do not have independent
versions.

## Supported versions

| Stored version          | Status        | Backend interpretation                                                           | Editor behaviour                           |
| ----------------------- | ------------- | -------------------------------------------------------------------------------- | ------------------------------------------ |
| Missing, `null`, or `0` | Deprecated v0 | Historical mixed literal/JSONata scalar semantics; PDF and HTML remain supported | Upgraded to v1 in memory with a notice     |
| `1`                     | Current v1    | Expression-capable scalar values are JSONata; output must resolve to PDF         | Edited and saved directly as v1            |
| Any other value         | Unsupported   | Rejected with a compatibility error                                              | Never silently downgraded or treated as v0 |

The backend receives the properties through Valtimo's injected
`@PluginActionProperty` parameters during execution. Preview, retry, and admin
flows read the same persisted `actionProperties` object from the process link.
Both sources are converted to `GenerateDocumentActionProperties` and dispatched
through the same version registry.

## Deprecated v0 behaviour

V0 exists only to keep already deployed process links executable. Its parser is
frozen compatibility code: it should change only when required to retain safe
execution of existing actions.

V0 scalar fields may contain either literals or JSONata expressions. The
backend represents values interpreted as literals with the deprecated
`LiteralScalar` type. Output format and correlation ID are always literal in v0;
other scalar fields use the historical expression-detection behaviour.

Opening a v0 action in the Valtimo editor converts the complete action to v1 in
frontend memory. This does not call a migration endpoint or update PostgreSQL.
The editor shows a notice while this in-memory upgrade is pending. Saving the
containing process persists the v1 representation; cancelling leaves the stored
v0 action unchanged.

The historical scalar heuristic is intentionally compatibility behaviour rather
than a general-purpose expression detector. Explicitly quoted JSONata strings
and syntactically valid values containing legacy markers are expressions;
ordinary values and malformed expression-like values are literals. This means
some surprising strings containing `&` or parentheses can be interpreted as
expressions in v0. V1 avoids that ambiguity by requiring string literals to be
quoted explicitly.

Frontend and backend tests consume
`test-fixtures/generate-document-v0-scalar-compatibility.json`. Any required v0
compatibility correction must update or extend this shared corpus so the
JavaScript and Java JSONata parsers remain aligned.

## Current v1 behaviour

V1 stores filename, correlation ID, environment, variant, and variant-attribute
values as JSONata expressions. A constant string is therefore stored as an
explicit JSONata string literal, for example `"value.pdf"` or `"nl"`. Dropdown
selections for variants and environments are serialized in that form.

Data mapping is always JSONata. Output format is stored as the fixed JSONata
expression `"PDF"` and must resolve to PDF at runtime.

The v1 editor presents free-form expressions through a guided visual control
where plain typing creates a quoted JSONata string, references are inserted as
semantic `$doc`/`$pv`/`$case` chips, and simple concatenations can be assembled
without writing syntax. Complex scalar expressions remain editable in the
control's validated, growing Advanced textarea. The data-mapping Simple mode
uses the same control for each statically named object field; mappings with a
dynamic outer structure use the Monaco-based whole-mapping Advanced editor.

Variant and environment use the same editor with resource options enabled,
adding a Select view to Visual and Advanced. The single mode action cycles in
the order Select → Visual → Advanced while skipping any view that cannot
represent the current expression losslessly. An exact loaded string can use all
three views, a simple dynamic or unmatched expression uses Visual and Advanced,
and complex JSONata remains Advanced-only.

This editor representation does not introduce a new action configuration
version. Both visual and Advanced modes read and write the same v1 JSONata
strings, so switching presentation mode does not change the persisted schema or
backend behaviour. A future `actionConfigVersion` is needed only when the
meaning or shape of the complete stored action changes.

## Removal policy

The Java v0 parser, backend `LiteralScalar`, and frontend v0 configuration type
are marked deprecated for removal. Their removal is a breaking change and may
only occur in a future major release after operators have had an announced
migration window for remaining v0 process links.
