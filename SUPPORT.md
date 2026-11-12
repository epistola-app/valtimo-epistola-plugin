# Release & Support Policy

The Valtimo Epistola Plugin is published and supported by **Epistola Nederland B.V.** and follows
the same policy as Epistola Suite
([`SUPPORT_POLICY.md`](https://github.com/epistola-app/epistola-suite/blob/main/SUPPORT_POLICY.md)):
**continuous delivery**, cheap upgrades, and rapid bug and security fixes, rather than long-lived
maintenance of old versions. Installations are expected to stay on a **supported release**.

This page states what that means for the plugin, and what the plugin adds: it sits between two
platforms, Valtimo and Epistola Suite, and is only as supported as the versions it runs with.

## Versioning

The plugin uses [Semantic Versioning](https://semver.org/).

| Change              | Version                      | Meaning                                            |
| ------------------- | ---------------------------- | -------------------------------------------------- |
| **Patch** (`x.y.z`) | Bug fixes, security fixes    | No breaking changes                                |
| **Minor** (`x.y.0`) | New functionality            | Backwards compatible within the same major version |
| **Major** (`x.0.0`) | May include breaking changes | Migration guidance is always provided              |

The backend (`app.epistola.valtimo:epistola-plugin`) and the frontend
(`@epistola.app/valtimo-plugin`) are released together under one version. Use the same version of
both.

There is **no fixed calendar**: releases are published when ready. Patch releases can be frequent,
minor releases come regularly, and a major only when needed.

## What 1.x keeps working

Within a major version the plugin preserves what an installation configures and stores, because
that outlives the code that wrote it:

- **Plugin configurations**: the plugin properties (`baseUrl`, `apiKey`, `tenantId`,
  `defaultEnvironmentId`, `templateSyncEnabled`).
- **Process links**: the action types (`epistola-generate-document`,
  `epistola-generate-dynamic-document`, `epistola-check-job-status`, `epistola-download-document`)
  and the properties they store.
- **Form definitions**: the settings of the plugin's Form.io components, including the letter
  composer's, which carry a `schemaVersion`.
- **Process variables** the plugin writes and reads, including a composed letter, which also carries
  a `schemaVersion`.
- **Documented configuration**: the `epistola.*` Spring properties and the `EPISTOLA_ENABLED`
  frontend setting.
- **Documented extension points**: custom expression functions, rich-text formats
  (`RichTextFormat`) and editors (`RichTextEditor`).

A later 1.x release reads every stored shape an earlier 1.x wrote; a shape that changes raises its
schema version and keeps reading the older ones. An older plugin refuses a newer shape with a
message naming both versions, rather than reading half of it.

The REST endpoints under `/api/v1/plugin/epistola` serve the plugin's own frontend. They are not a
public API and may change in a minor release; the frontend and backend of one version always match.

Deprecated functionality is announced in the [CHANGELOG](CHANGELOG.md) before removal whenever
reasonably possible, and breaking changes are called out under its _Upgrading_ heading.

## Supported versions

Within a major version, only the **newest minor release line** is patched. A line stays supported
for a fixed time after its successor ships, so there is always room to upgrade:

| When                                             | The line it replaces stays supported for |
| ------------------------------------------------ | ---------------------------------------- |
| A new **minor** ships (1.1.0 after 1.0.x)        | **30 days**                              |
| A new **major** ships (1.0.0 after the last 0.x) | **3 months**                             |

**The newest minor line** receives bug fixes, security fixes, documentation updates, and technical
support where an arrangement with Epistola Nederland B.V. applies.

**The previous minor line, during its 30 days,** receives technical support. Its fixes ship in the
newest minor, which is backwards compatible: upgrading to it is the fix.

**The last minor line of the previous major, during its three months,** receives security fixes and
fixes for data loss or corruption, as patch releases, and technical support. It receives no other
bug fixes and no new features. For 1.0.0 that line is **0.21.x**, which works with Epistola Suite
1.x; three months is also how long the Suite supports its own last 1.x line after Suite 2.0.0, so
an installation can move both together within that window.

**Pre-releases** (alpha, beta, release candidate) are for evaluation, not production. A fix reaches
them in the next pre-release, or in the release they lead up to.

**Unsupported** versions receive **no** updates.

An end date may be **extended, never brought forward**. The supported versions are published as
release metadata, and the plugin's admin page reads it: it warns when a newer version is available,
when the running version is no longer supported, and when support is ending soon (see the
[README](README.md); `epistola.version-check.enabled=false` turns the check off).

## Platform compatibility

A plugin release is supported on the platform versions it is compatible with, as recorded in
[COMPATIBILITY.md](COMPATIBILITY.md):

- **Valtimo**: a range within one Valtimo major, kept as wide as is reasonable. A minor plugin
  release may raise the floor of that range only when there is a clear benefit, and says so in the
  CHANGELOG and in COMPATIBILITY.md.
- **Epistola Suite**: one Suite major. Plugin 1.x speaks Epistola contract 2.0 and needs Suite 2.x;
  no 1.x Suite can serve it.

A problem is supported on a supported plugin release running on supported versions of both
platforms.

## Upgrades

- **Direct upgrades** from any supported version to the latest, without intermediate releases.
- **Upgrade notes** for every release, under _Upgrading_ in the [CHANGELOG](CHANGELOG.md), and a
  guide for a major: [Upgrading from 0.x to 1.0](docs/upgrading-from-0.x.md).
- **Rollback of a failed upgrade** is supported: back up Valtimo's database before upgrading and
  restore it if the upgrade does not complete.
- **Downgrades are not supported.** Once a newer version has written configuration or process
  variables, an older one may refuse them (see _What 1.x keeps working_).

## Security

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) describes. Fixes ship as patch
releases on the supported lines above.

## Getting help

- **Questions and bugs**: open an issue on
  [GitHub](https://github.com/epistola-app/valtimo-epistola-plugin/issues), with the plugin,
  Valtimo and Epistola Suite versions involved. Report issues against a **supported** release.
- **Technical support** under an arrangement: contact Epistola Nederland B.V.
- **Release announcements**:
  [GitHub Releases](https://github.com/epistola-app/valtimo-epistola-plugin/releases).
