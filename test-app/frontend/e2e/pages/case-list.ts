// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/**
 * The case list's button that starts a new case, by its accessible name.
 *
 * Valtimo 13.53 moved it to the translation key `Start Case` ("Nieuw dossier aanmaken"); before
 * that it was `Start Dossier`, which the test-app translates as "Creëer Nieuw Dossier". Both are
 * accepted so the suites run on either side of that change. An empty case list shows the button
 * twice (header and empty state), so take `.first()`.
 */
export const START_CASE_BUTTON =
  /Nieuw dossier aanmaken|Creëer Nieuw Dossier|Start Dossier|Start Case/;
