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
 * <p>Includes the labels for the `epistola-generate-dynamic-document` action: it is declared on
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
    composerPreviewUpdating: 'Bijwerken…',
    composerPreviewStale: 'Niet bijgewerkt',
    composerAutoRefresh: 'Automatisch bijwerken',
    composerRefresh: 'Vernieuwen',
    composerResizePreview: 'Voorbeeld groter of kleiner maken',
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
    composerRefusedFields: 'Deze gegevens houden de brief tegen:',
    // Write-back builder (settings)
    writeBackIntro:
      'Waarden uit de brief blijven standaard bij de brief. Voeg een regel toe voor elke waarde die ook in het dossier of een procesvariabele hoort.',
    writeBackDestination: 'Bestemming',
    writeBackExpression: 'Expressie over de brief',
    writeBackAdd: 'Regel toevoegen',
    writeBackRemove: 'Regel verwijderen',
    writeBackHintData: 'is de brief zoals die verstuurd wordt,',
    writeBackHintInputs: 'alleen wat de medewerker zelf heeft ingevuld.',
    writeBackDestinationInvalid:
      'Gebruik doc:/pad/naar/veld voor het dossier of pv:naam voor een procesvariabele.',
    writeBackDestinationDuplicate: 'Deze bestemming heeft al een regel.',
    writeBackDestinationMissing: 'Vul een bestemming in, anders wordt deze regel niet opgeslagen.',
    writeBackExpressionInvalid: 'Deze JSONata-expressie kan niet worden gelezen.',
    writeBackExpressionMissing: 'Vul een expressie in, anders wordt deze regel niet opgeslagen.',
    letterSetConnection: 'Epistola-verbinding',
    letterSetReadBy: 'Deze brief wordt gegenereerd door',
    letterSetReadByIn: 'in',
    letterSetReadByNothing:
      'Geen genereertaak op dit dossiertype leest deze brief. Dat hoeft niet fout te zijn — een brief kan ook in een ander proces worden gegenereerd.',
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
    letterSetTemplateMissing:
      'Deze brieven staan nog in deze component, maar bestaan niet meer in de catalogus. Een medewerker die er een kiest, krijgt een foutmelding.',
    letterSetTemplateMissingDrop: 'Verwijderen uit de set',
    letterSetEnvironment: 'Omgeving',
    letterSetEnvironmentTooltip:
      'Waar de brieven gegenereerd worden, en waaruit hun sjablonen gelezen worden. Een catalogus hieronder die niet in deze omgeving staat, is gemarkeerd. Kies "Expressie" om de omgeving per zaak te bepalen, bijvoorbeeld met $pv.omgeving.',
    letterSetEnvironmentDefault: '— standaardomgeving van de verbinding —',
    letterSetEnvironmentExpression: 'Expressie (JSONata)…',
    letterSetExpression: 'Expressie',
    letterSetCatalogNotListed:
      'Deze catalogus bestaat niet (meer) in Epistola. Kies een andere catalogus.',
    letterSetVersionRangeTooltip:
      'Welke release van de catalogus de brieven gebruiken. Een versie (1.4.0) zet hem vast; een bereik (^1.4.0) neemt de hoogste release die eraan voldoet. Vink "Expressie" aan om hem per zaak te bepalen met JSONata. Leeg: de release die in de omgeving staat.',
    letterSetTemplateVersionRange: 'Eigen release voor deze brief',
    letterSetTemplateVersionRangeTooltip:
      'Optioneel. Een versie of bereik alleen voor deze brief, in plaats van die van de set. Laat leeg om de release van de set te gebruiken.',
    letterSetConfigure: 'Instellen',
    letterSetConfigureClose: 'Sluiten',
    letterSetTemplateMapping: 'Eigen mapping voor deze brief',
    letterSetTemplateMappingTooltip:
      'Optioneel. JSONata die over de basismapping heen wordt gelegd, alleen voor deze brief. Laat leeg als de basismapping volstaat.',
    'epistola-generate-dynamic-document': 'Genereer Dynamisch Document',
    composedLetterVariable: 'Documentvariabele',
    composedLetterVariableTooltip:
      'Naam van de procesvariabele met het te genereren document. Standaard epistolaLetter. De variabele bevat de catalogus, het sjabloon en de gegevens, dus deze actie heeft zelf geen sjabloon of mapping nodig. Meestal gezet door een briefkiezer, maar een proces mag hem ook zelf vullen.',
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
    composerPreviewUpdating: 'Updating…',
    composerPreviewStale: 'Not up to date',
    composerAutoRefresh: 'Update automatically',
    composerRefresh: 'Refresh',
    composerResizePreview: 'Resize the preview',
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
    composerRefusedFields: 'These values are stopping the letter:',
    writeBackIntro:
      "A letter's values stay with the letter by default. Add a rule for each value that also belongs in the case or in a process variable.",
    writeBackDestination: 'Destination',
    writeBackExpression: 'Expression over the letter',
    writeBackAdd: 'Add a rule',
    writeBackRemove: 'Remove this rule',
    writeBackHintData: 'is the letter as it will be sent,',
    writeBackHintInputs: 'only what the employee typed themselves.',
    writeBackDestinationInvalid:
      'Use doc:/path/to/field for the case document, or pv:name for a process variable.',
    writeBackDestinationDuplicate: 'This destination already has a rule.',
    writeBackDestinationMissing: 'Give it a destination, or this rule is not saved.',
    writeBackExpressionInvalid: 'This JSONata expression cannot be read.',
    writeBackExpressionMissing: 'Give it an expression, or this rule is not saved.',
    letterSetConnection: 'Epistola connection',
    letterSetReadBy: 'This letter is generated by',
    letterSetReadByIn: 'in',
    letterSetReadByNothing:
      'No generate task on this case type reads this letter. That need not be wrong — a letter can also be generated in another process.',
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
    letterSetTemplateMissing:
      'These letters are still configured here but no longer exist in the catalog. An employee who picks one gets an error.',
    letterSetTemplateMissingDrop: 'Remove from the set',
    letterSetEnvironment: 'Environment',
    letterSetEnvironmentTooltip:
      'Where the letters are generated, and where their templates are read from. A catalog below that is not deployed to this environment is marked. Choose "Expression" to decide the environment per case, for example with $pv.omgeving.',
    letterSetEnvironmentDefault: "— the connection's default environment —",
    letterSetEnvironmentExpression: 'Expression (JSONata)…',
    letterSetExpression: 'Expression',
    letterSetCatalogNotListed:
      'This catalog does not exist in Epistola (any more). Choose another catalog.',
    letterSetVersionRangeTooltip:
      'Which release of the catalog the letters use. A version (1.4.0) pins one; a range (^1.4.0) takes the highest release that satisfies it. Tick "Expression" to decide it per case with JSONata. Blank: the release deployed to the environment.',
    letterSetTemplateVersionRange: 'Release for this letter',
    letterSetTemplateVersionRangeTooltip:
      "Optional. A version or range for this letter only, instead of the set's. Leave blank to use the set's release.",
    letterSetConfigure: 'Configure',
    letterSetConfigureClose: 'Close',
    letterSetTemplateMapping: 'Mapping for this letter only',
    letterSetTemplateMappingTooltip:
      'Optional. JSONata merged over the baseline mapping, for this letter alone. Leave empty when the baseline is enough.',
    'epistola-generate-dynamic-document': 'Generate Dynamic Document',
    composedLetterVariable: 'Document variable',
    composedLetterVariableTooltip:
      'Name of the process variable holding the document to render. Defaults to epistolaLetter. It holds the catalog, the template and the data, so this action needs no template or mapping of its own. Usually written by a letter composer, but a process may set it itself.',
    composedFilename: 'Filename (optional)',
    composedFilenameTooltip:
      "JSONata expression for the filename. Leave empty to use the template's name.",
  },
} as const;
