# Generate-document action versioning

The `epistola-generate-document` process-link action stores its configuration in
Valtimo's `actionProperties` JSON. `actionConfigVersion` defines how the complete
action object is interpreted; individual fields do not have independent
versions.

## Supported versions

| Stored version          | Status        | Backend interpretation                                                           | Editor behaviour                           |
| ----------------------- | ------------- | -------------------------------------------------------------------------------- | ------------------------------------------ |
| Missing, `null`, or `0` | Deprecated v0 | Historical mixed literal/JSONata scalar semantics; PDF and HTML remain supported | Upgraded to v1 in memory when opened       |
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
Saving the containing process persists the v1 representation; cancelling leaves
the stored v0 action unchanged.

## Current v1 behaviour

V1 stores filename, correlation ID, environment, variant, and variant-attribute
values as JSONata expressions. A constant string is therefore stored as an
explicit JSONata string literal, for example `"value.pdf"` or `"nl"`. Dropdown
selections for variants and environments are serialized in that form.

Data mapping is always JSONata. Output format is stored as the fixed JSONata
expression `"PDF"` and must resolve to PDF at runtime.

## Removal policy

The Java v0 parser, backend `LiteralScalar`, and frontend v0 configuration type
are marked deprecated for removal. Their removal is a breaking change and may
only occur in a future major release after operators have had an announced
migration window for remaining v0 process links.
