# Changelog: test app

The test app (`test-app/`) is the Valtimo application this repository uses to develop, test and
demo the plugin. It is not part of the plugin; changes to the plugin itself are in the
[plugin changelog](../CHANGELOG.md). Test app changes before this file existed are listed there.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **A letter about an event, sent ad hoc from the case.** The case type `evenement` gets
  a process "Losse brief" with the letter composer on its start form, offering two letters from
  the bundled catalog `gzac-uitnodiging` (now 1.3.0): badges with a QR code for every participant,
  and an attendance list per daypart. The case supplies the event, the programme and the
  participants; each letter asks the employee for a few fields of its own (the kind of badge and
  what its QR code holds, the daypart and room of the list), with a live preview before anything
  is generated.
- **A reminder to one participant of an event.** The process "Herinnering sturen" on
  `evenement` picks the participant from the case in its start form, takes an optional note and
  previews the reminder before generating it. No code: a select reads the participant list the
  form is prefilled with from the case document (a plain `deelnemers` key), and the
  generate-document mapping finds the chosen
  participant by e-mail address. The letter composer cannot offer a choice whose options come from
  the case, which is why this one has a process of its own. Both built for the TechTalk on the
  GZAC user day of 17 November 2026.
- **The event's sign-up form previews the invitation.** "Aanmelden" on `evenement` shows
  the invitation next to the form while it is filled in. "Ik kom naar" is now a multi-select, so
  the preview gets the chosen dayparts as the same list the invitation is generated from. The
  invitation's contract requires a participant name of at least one character, so the preview
  waits for one instead of rendering a letter addressed to nobody.
