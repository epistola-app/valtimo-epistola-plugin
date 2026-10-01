/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

/**
 * Everything the letter composer says, in both languages.
 *
 * <p>Kept in the module rather than in `epistola.specification.ts` for the same reason its code,
 * endpoints and components are: the composer is a feature of its own, and deleting `lib/composer/`
 * should not leave its strings behind in a file that describes the rest of the plugin. The
 * specification spreads these into `pluginTranslations`, which is the one shape Valtimo consumes.
 *
 * <p>Includes the labels for the `epistola-generate-composed-document` action: it is declared on
 * the plugin class because Valtimo scans that class for actions, but it exists only to render what
 * a composer chose.
 */
export const COMPOSER_TRANSLATIONS = {
  nl: {
    // Letter composer (settings: the baseline mapping field)
    mappingFieldInvalid: 'Ongeldige JSONata',
    mappingFieldReads: 'Leest',
    mappingFieldReadsNothing: 'Leest geen document- of procesgegevens',
    // Letter composer (runtime UI)
    composerChooseLetter: 'Kies een brief',
    composerChoosePlaceholder: '— kies een brief —',
    composerNeedsContext:
      'Een brief opstellen kan alleen vanuit een gebruikerstaak of een startformulier van een lopend dossier.',
    composerSection: 'Stap {step}',
    composerPreparing: 'Brief voorbereiden…',
    composerNothingToAsk: 'Deze brief heeft geen aanvullende invoer nodig.',
    composerPreview: 'Voorbeeld',
    composerPreviewLoading: 'Voorbeeld genereren…',
    composerPreviewUnsupported: 'PDF-voorbeeld wordt niet ondersteund in deze browser.',
    composerAwaitingInput: 'Vul de velden in om de brief te zien.',
    composerPrepareFailed: 'Deze brief kon niet worden voorbereid.',
    composerPreviewFailed: 'Voorbeeld kon niet worden gegenereerd.',
    // What the browser says when a generated input breaks the template's own rule. `{{field}}`,
    // `{{length}}`, `{{min}}` and `{{max}}` are interpolated by Form.io; `{{description}}` by this
    // plugin. Never `{{pattern}}` — see composer-messages.ts.
    composerValidationRequired: '{{field}} is verplicht.',
    composerValidationMinLength: '{{field}} moet minimaal {{length}} tekens bevatten.',
    composerValidationMaxLength: '{{field}} mag maximaal {{length}} tekens bevatten.',
    composerValidationMin: '{{field}} mag niet lager zijn dan {{min}}.',
    composerValidationMax: '{{field}} mag niet hoger zijn dan {{max}}.',
    composerValidationPattern: '{{field}} heeft niet de juiste vorm.',
    composerValidationPatternDescribed: '{{field}} heeft niet de juiste vorm: {{description}}',
    composerValidationExample: ' Bijvoorbeeld: {{example}}',
    letterSetConnection: 'Epistola-verbinding',
    letterSetChooseConnection: '— kies een verbinding —',
    letterSetCatalog: 'Catalogus',
    letterSetChooseCatalog: '— kies een catalogus —',
    letterSetLetters: 'Beschikbare brieven',
    letterSetLoading: 'Laden…',
    letterSetLoadingTemplates: 'Sjablonen laden…',
    letterSetCatalogFirst: 'Kies een catalogus om de sjablonen te zien.',
    letterSetLabelPlaceholder: 'Label voor de behandelaar',
    letterSetConnectionsFailed: 'De Epistola-verbindingen konden niet worden geladen.',
    letterSetCatalogsFailed: 'De catalogi van deze verbinding konden niet worden geladen.',
    letterSetTemplatesFailed: 'De sjablonen van deze catalogus konden niet worden geladen.',
    'epistola-generate-composed-document': 'Genereer Gekozen Brief',
    composedLetterVariable: 'Briefvariabele',
    composedLetterVariableTooltip:
      'Naam van de procesvariabele waarin de briefkiezer de gekozen brief heeft gezet. Standaard epistolaLetter. De variabele bevat de catalogus, het sjabloon en de gegevens, dus deze actie heeft zelf geen sjabloon of mapping nodig.',
    composedFilename: 'Bestandsnaam (optioneel)',
    composedFilenameTooltip:
      'JSONata-expressie voor de bestandsnaam. Leeg laten om de naam van het gekozen sjabloon te gebruiken.',
  },
  en: {
    // Letter composer (settings: the baseline mapping field)
    mappingFieldInvalid: 'Invalid JSONata',
    mappingFieldReads: 'Reads',
    mappingFieldReadsNothing: 'Reads no document or process data',
    // Letter composer (runtime UI)
    composerChooseLetter: 'Choose a letter',
    composerChoosePlaceholder: '— choose a letter —',
    composerNeedsContext:
      'Composing a letter is only available from a user task, or from the start form of an open case.',
    composerSection: 'Step {step}',
    composerPreparing: 'Preparing letter…',
    composerNothingToAsk: 'This letter needs no further input.',
    composerPreview: 'Preview',
    composerPreviewLoading: 'Generating preview…',
    composerPreviewUnsupported: 'PDF preview not supported in this browser.',
    composerAwaitingInput: 'Fill in the fields to see the letter.',
    composerPrepareFailed: 'This letter could not be prepared.',
    composerPreviewFailed: 'Preview could not be generated.',
    composerValidationRequired: '{{field}} is required.',
    composerValidationMinLength: '{{field}} must have at least {{length}} characters.',
    composerValidationMaxLength: '{{field}} must have no more than {{length}} characters.',
    composerValidationMin: '{{field}} cannot be less than {{min}}.',
    composerValidationMax: '{{field}} cannot be greater than {{max}}.',
    composerValidationPattern: '{{field}} is not in the right form.',
    composerValidationPatternDescribed: '{{field}} is not in the right form: {{description}}',
    composerValidationExample: ' For example: {{example}}',
    letterSetConnection: 'Epistola connection',
    letterSetChooseConnection: '— choose a connection —',
    letterSetCatalog: 'Catalog',
    letterSetChooseCatalog: '— choose a catalog —',
    letterSetLetters: 'Letters on offer',
    letterSetLoading: 'Loading…',
    letterSetLoadingTemplates: 'Loading templates…',
    letterSetCatalogFirst: 'Choose a catalog to see its templates.',
    letterSetLabelPlaceholder: 'Label for the employee',
    letterSetConnectionsFailed: 'Could not load the Epistola connections.',
    letterSetCatalogsFailed: 'Could not load the catalogs of this connection.',
    letterSetTemplatesFailed: 'Could not load the templates of this catalog.',
    'epistola-generate-composed-document': 'Generate Chosen Letter',
    composedLetterVariable: 'Letter variable',
    composedLetterVariableTooltip:
      'Name of the process variable the letter composer wrote the chosen letter to. Defaults to epistolaLetter. It holds the catalog, the template and the data, so this action needs no template or mapping of its own.',
    composedFilename: 'Filename (optional)',
    composedFilenameTooltip:
      "JSONata expression for the filename. Leave empty to use the chosen template's name.",
  },
} as const;
