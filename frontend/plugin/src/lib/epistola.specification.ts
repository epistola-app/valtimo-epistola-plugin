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

import { PluginSpecification } from '@valtimo/plugin';
import { EpistolaConfigurationComponent } from './components/epistola-configuration/epistola-configuration.component';
import { GenerateDocumentConfigurationComponent } from './components/generate-document-configuration/generate-document-configuration.component';
import { CheckJobStatusConfigurationComponent } from './components/check-job-status-configuration/check-job-status-configuration.component';
import { DownloadDocumentConfigurationComponent } from './components/download-document-configuration/download-document-configuration.component';
import { EPISTOLA_PLUGIN_LOGO_BASE64 } from './assets';
import { isEpistolaEnabled } from './epistola-runtime-config';

const EPISTOLA_PLUGIN_ID = 'epistola';
const DISABLED_EPISTOLA_PLUGIN_ID = '__epistola_disabled__';

const epistolaPluginSpecification: PluginSpecification = {
  get pluginId(): string {
    return isEpistolaEnabled() ? EPISTOLA_PLUGIN_ID : DISABLED_EPISTOLA_PLUGIN_ID;
  },

  // Component for plugin-level configuration (tenantId)
  pluginConfigurationComponent: EpistolaConfigurationComponent,

  // Plugin logo
  pluginLogoBase64: EPISTOLA_PLUGIN_LOGO_BASE64,

  // Map action keys to their configuration components
  functionConfigurationComponents: {
    'epistola-generate-document': GenerateDocumentConfigurationComponent,
    'epistola-check-job-status': CheckJobStatusConfigurationComponent,
    'epistola-download-document': DownloadDocumentConfigurationComponent,
  },

  // Translations
  pluginTranslations: {
    nl: {
      title: 'Epistola Document Suite',
      description: 'Documentgeneratie met Epistola',
      configurationTitle: 'Configuratienaam',
      baseUrl: 'Base URL',
      baseUrlTooltip: 'De basis URL van de Epistola API (bijv. https://api.epistola.app)',
      apiKey: 'API Key',
      apiKeyTooltip: 'De API sleutel voor authenticatie met Epistola',
      tenantId: 'Tenant ID',
      tenantIdTooltip:
        'De tenant slug in Epistola (3-63 tekens, alleen kleine letters, cijfers en koppeltekens, bijv. "mijn-tenant")',
      defaultEnvironmentId: 'Standaard Omgeving',
      defaultEnvironmentIdTooltip:
        'De standaard omgeving voor documentgeneratie (3-30 tekens, alleen kleine letters, cijfers en koppeltekens, bijv. "productie")',
      templateSyncEnabled: 'Template synchronisatie',
      templateSyncEnabledTooltip:
        'Synchroniseer template definities automatisch van het classpath naar Epistola bij het opstarten',
      'epistola-generate-document': 'Genereer Document',
      catalogId: 'Catalogus',
      catalogIdTooltip: 'Selecteer de catalogus waaruit een template gekozen wordt',
      templateId: 'Template',
      templateIdTooltip: 'Selecteer het template dat gebruikt wordt voor documentgeneratie',
      variantId: 'Variant',
      variantIdTooltip: 'Selecteer de template variant',
      variantSelectionMode: 'Variant selectie',
      variantSelectionModeTooltip: 'Kies hoe de variant geselecteerd wordt',
      selectByVariant: 'Selecteer variant',
      selectByAttributes: 'Selecteer op kenmerken',
      variantAttributes: 'Variant kenmerken',
      variantAttributesTooltip:
        'Kenmerken voor automatische variant selectie. Waarden kunnen expressies zijn (doc:, pv:, case:).',
      attributeKey: 'Kenmerk',
      attributeKeyCustom: 'Aangepast...',
      attributeValue: 'Waarde',
      addAttribute: 'Kenmerk toevoegen',
      removeAttribute: 'Kenmerk verwijderen',
      attributeRequired: 'Verplicht',
      attributePreferred: 'Voorkeur',
      environmentId: 'Omgeving',
      environmentIdTooltip: 'Selecteer de doelomgeving (optioneel)',
      correlationId: 'Correlatie ID',
      correlationIdTooltip: 'Een optioneel correlatie ID voor het traceren van dit verzoek',
      dataMapping: 'Data Mapping',
      dataMappingTooltip: 'Koppeling van template velden naar data bronnen (doc:, pv:, case:)',
      outputFormat: 'Uitvoerformaat',
      outputFormatTooltip: 'Het gewenste formaat van het gegenereerde document',
      filename: 'Bestandsnaam',
      filenameTooltip: 'De bestandsnaam voor het gegenereerde document',
      resultProcessVariable: 'Resultaat Procesvariabele',
      resultProcessVariableTooltip:
        'De naam van de procesvariabele waarin het request ID wordt opgeslagen',
      resultProcessVariableInvalid: 'Gebruik alleen letters en cijfers (A-Z, a-z, 0-9).',
      pdf: 'PDF',
      html: 'HTML',
      // Data mapping builder translations
      dataMappingTitle: 'Data Mapping',
      dataMappingDescription: 'Koppel template velden aan Valtimo data bronnen',
      jsonataDescription:
        'JSONata expressie die de template data genereert. Gebruik $doc, $pv en $case voor toegang tot document-, procesvariabelen- en zaakdata.',
      mappingModeSimple: 'Eenvoudig',
      mappingModeAdvanced: 'Geavanceerd',
      mappingTools: 'Schema & Voorbeeld',
      expectedStructure: 'Verwacht schema',
      expectedStructureLoading: 'Schema laden...',
      previewTitle: 'Voorbeeld',
      previewDocPlaceholder: 'Document ID',
      previewExpected: 'Verwacht',
      previewProduced: 'Geproduceerd',
      previewRunHint: 'Voer een document ID in en klik ▶ om een voorbeeld te zien',
      previewMissing: 'Ontbrekende verplichte velden',
      templateField: 'Template veld',
      dataSource: 'Data bron',
      addMapping: 'Mapping toevoegen',
      noMappings: 'Nog geen mappings toegevoegd. Klik op "Mapping toevoegen" om te beginnen.',
      documentFields: 'Document velden',
      processVariables: 'Procesvariabelen',
      caseProperties: 'Zaak eigenschappen',
      sourceType: 'Brontype',
      sourceTypeDocument: 'Document veld',
      sourceTypeProcessVariable: 'Procesvariabele',
      sourceTypeManual: 'Handmatig',
      requiredFieldsMissing: 'Niet alle verplichte velden zijn gekoppeld',
      requiredFieldsComplete: 'Alle verplichte velden zijn gekoppeld',
      validationSummary: 'verplichte velden gekoppeld',
      jsonataValidationErrorsHeading: 'Ongeldige JSONata-expressies:',
      fieldRequired: 'Verplicht',
      fieldOptional: 'Optioneel',
      mapCollectionTo: 'Koppel collectie aan',
      browseMode: 'Bladermodus',
      pvMode: 'Procesvariabele modus',
      pvPlaceholder: 'Naam procesvariabele',
      expressionMode: 'Expressiemodus',
      availableFunctions: 'Beschikbare functies',
      itemFieldMapping: 'Veldnamen per item koppelen',
      itemFieldMappingTitle: 'Veldkoppeling per item:',
      sourceFieldPlaceholder: 'Bronveldnaam',
      noTemplateFields: 'Geen template velden beschikbaar',
      // Check job status action
      'epistola-check-job-status': 'Controleer Taakstatus',
      requestIdVariable: 'Request ID Variabele',
      requestIdVariableTooltip: 'Naam van de procesvariabele met het Epistola request ID',
      statusVariable: 'Status Variabele',
      statusVariableTooltip: 'Naam van de procesvariabele waarin de status wordt opgeslagen',
      documentIdVariable: 'Document ID Variabele',
      documentIdVariableTooltip:
        'Naam van de procesvariabele waarin het document ID wordt opgeslagen (bij voltooiing)',
      errorMessageVariable: 'Foutmelding Variabele',
      errorMessageVariableTooltip:
        'Naam van de procesvariabele waarin de foutmelding wordt opgeslagen (bij fout)',
      // Download document action
      'epistola-download-document': 'Download Document',
      documentVariable: 'Document Variabele',
      documentVariableTooltip:
        'Naam van de procesvariabele met het Epistola resultaat. Mag een String document ID zijn (legacy) of een rich-result object met een documentId-veld; de actie haalt het document ID eruit.',
      storageTarget: 'Opslagdoel',
      storageTargetTooltip:
        'Waar het gedownloade PDF wordt opgeslagen. "Tijdelijke resource-opslag" (aanbevolen) zet alleen een resource-id in de variabele — geschikt om door te geven aan documenten-api:store-temp-document. "Procesvariabele" zet de ruwe bytes inline in de variabele (alleen voor kleine, niet-gevoelige documenten; deze worden in de taakrespons meegestuurd).',
      resourceIdVariable: 'Resource-ID Variabele',
      resourceIdVariableTooltip:
        'Naam van de procesvariabele waarin het tijdelijke resource-id wordt opgeslagen (geef deze door aan documenten-api:store-temp-document).',
      contentVariable: 'Inhoud Variabele',
      contentVariableTooltip:
        'Naam van de procesvariabele waarin de ruwe PDF-bytes inline worden opgeslagen (alleen voor kleine, niet-gevoelige documenten).',
      // Admin page
      epistolaAdminOverview: 'Overzicht',
      epistolaAdminRefresh: 'Vernieuwen',
      epistolaAdminLoading: 'Laden...',
      epistolaAdminNoConfigurations: 'Geen Epistola plugin configuraties gevonden.',
      epistolaAdminTenantId: 'Tenant ID',
      epistolaAdminStatus: 'Status',
      epistolaAdminConnected: 'Verbonden',
      epistolaAdminUnreachable: 'Onbereikbaar',
      epistolaAdminError: 'Fout',
      epistolaAdminPluginActions: 'Plugin acties',
      epistolaAdminProblems: 'Problemen',
      epistolaAdminBackToOverview: 'Terug naar overzicht',
      epistolaAdminNoUsageForConfig: 'Geen procesacties geconfigureerd voor deze verbinding.',
      epistolaAdminCase: 'Zaak',
      epistolaAdminProcess: 'Proces',
      epistolaAdminActivity: 'Activiteit',
      epistolaAdminAction: 'Actie',
      epistolaAdminServerVersion: 'Server versie',
      epistolaAdminContractVersion: 'Contract versie',
      epistolaAdminContractPluginVersion: 'Plugin contract',
      epistolaAdminContractServerVersion: 'Server contract',
      epistolaAdminContractCompatibility: 'Contract compatibiliteit',
      epistolaAdminContractWarning: 'Waarschuwing',
      epistolaAdminContractUnknown: 'Versie niet gemeld',
      epistolaAdminContractError: 'Niet compatibel',
      epistolaAdminContractWarningBody:
        'De Epistola server gebruikt een oudere minor contractversie dan deze plugin. Meestal is de suite backwards compatible, maar controleer de release notes voordat je doorgaat.',
      epistolaAdminContractUnknownBody:
        'De Epistola server meldt geen contractversie. Compatibiliteit kan niet worden gecontroleerd; upgrade of herdeploy de server met een contract build die versie-metadata publiceert.',
      epistolaAdminContractErrorBody:
        'De Epistola server en deze plugin gebruiken verschillende major contractversies. Dit kan documentgeneratie en catalogusuitrol breken; gebruik compatibele versies.',
      epistolaAdminExport: 'Exporteren',
      epistolaAdminPendingJobs: 'Wachtende taken',
      epistolaAdminNoPendingJobs: 'Geen wachtende taken voor deze verbinding.',
      epistolaAdminConfiguration: 'Configuratie',
      epistolaAdminRequestId: 'Request ID',
      epistolaAdminReconcile: 'Hersynchroniseer',
      epistolaAdminReconciling: 'Bezig...',
      epistolaAdminReconcileTooltip:
        'Vraag de huidige status op bij Epistola en hervat het wachtende proces als het klaar is.',
      epistolaAdminStatusWaiting: 'Wachtend',
      epistolaAdminStatusUnwired: 'Vastgelopen',
      epistolaAdminUnwiredTooltip:
        'Deze taak heeft geen correlatie-token (epistolaWaitFor), dus de collector kan hem nooit hervatten — het proces is vastgelopen. Meestal een dubbelzinnig samengevoegd catch event (zie BPMN-validatie). Op te lossen in het procesmodel; hersynchroniseren werkt hier niet.',
      epistolaAdminUnwiredHint: 'Niet te hersynchroniseren',
      epistolaAdminConfigurations: 'Configuraties',
      epistolaAdminValidations: 'BPMN-validatie',
      epistolaAdminNoValidations:
        'Geen race-onveilige procesdefinities gevonden. Alles ziet er goed uit.',
      epistolaAdminValidationWarningTitle: 'BPMN configuratie waarschuwing',
      epistolaAdminValidationWarningBody:
        'In deze procesdefinities is de grens tussen de generate-document service task en de EpistolaDocumentGenerated catch event niet synchroon. Resultaten kunnen verloren gaan; gebruik in dat geval de Hersynchroniseer-knop in de Wachtende taken-tab.',
      epistolaAdminValidationCode: 'Code',
      epistolaAdminValidationMessage: 'Bericht',
      epistolaAdminValidationLastChecked: 'Laatst gecontroleerd',
      epistolaAdminValidationNotYetRun: 'nog niet uitgevoerd',
      epistolaAdminValidationAutoRefresh: 'automatisch opnieuw gecontroleerd, ongeveer elke',
      epistolaAdminValidationLatestVersionNote:
        'Alleen de meest recente versie van elke procesdefinitie wordt gecontroleerd; oudere versies met nog lopende instanties kunnen problemen hebben die hier niet worden getoond.',
      epistolaAdminCatalogs: 'Catalogi',
      epistolaAdminCatalogsIntro:
        'Catalogi op het classpath van de applicatie. Deze worden bij het opstarten automatisch uitgerold; gebruik Opnieuw uitrollen om een catalogus handmatig (geforceerd) naar deze Epistola-omgeving te sturen, bijvoorbeeld als de automatische uitrol is mislukt.',
      epistolaAdminNoCatalogs: 'Geen catalogi gevonden op het classpath.',
      epistolaAdminCatalogSlug: 'Slug',
      epistolaAdminCatalogVersion: 'Versie',
      epistolaAdminCatalogStatus: 'Status in Epistola',
      epistolaAdminCatalogInEpistola: 'Aanwezig in Epistola',
      epistolaAdminCatalogNotInEpistola: 'Niet in Epistola',
      epistolaAdminCatalogStatusUnknown: 'Onbekend (Epistola niet bereikbaar)',
      epistolaAdminRedeploy: 'Opnieuw uitrollen',
      epistolaAdminRedeploying: 'Bezig...',
      epistolaAdminRedeployTooltip:
        'Bouw deze catalogus opnieuw vanaf het classpath en stuur hem geforceerd naar Epistola, ongeacht de versie of de templateSyncEnabled-instelling.',
      epistolaAdminChangelog: 'Changelog',
      epistolaAdminRunningVersion: 'Actieve plugin-versie:',
      epistolaAdminNoChangelog: 'Geen changelog beschikbaar in deze build.',
      // TEMPORARY (remove in 1.0.0): task-id carrier repair (admin "Forms" tab)
      epistolaAdminForms: 'Formulieren',
      epistolaAdminFormsIntro:
        'Formulieren met een Epistola-component dat het verborgen task-id veld mist. Zonder dat veld werkt het voorbeeld/downloaden/opnieuw genereren niet in elke taak-openflow. Herstel voegt het veld toe.',
      epistolaAdminFormName: 'Formulier',
      epistolaAdminFormMissing: 'Ontbrekende componenten',
      epistolaAdminFormReadOnly: 'Alleen-lezen',
      epistolaAdminFormReadOnlyHint:
        'Dit formulier komt van het classpath en wordt bij de volgende herstart teruggezet naar de bron. Voeg het veld toe aan de bron (component opnieuw plaatsen) voor een blijvende oplossing.',
      epistolaAdminRepair: 'Herstellen',
      epistolaAdminRepairAll: 'Alles herstellen',
      epistolaAdminRepairing: 'Bezig...',
      epistolaAdminRepairTooltip:
        'Voeg het verborgen task-id veld toe aan alle Epistola-componenten in dit formulier.',
      epistolaAdminNoFormIssues: 'Geen formulieren met een ontbrekend task-id veld gevonden.',
      // TEMPORARY: legacy override-mapping format detection (admin "Forms" tab)
      epistolaAdminLegacyOverrideTitle: 'Verouderd invoer-override formaat',
      epistolaAdminLegacyOverrideIntro:
        'Formulieren waarvan een document-voorbeeldcomponent de invoer-overrides nog als object opslaat ("form:"-verwijzingen) in plaats van als JSONata-expressie over $form. Ze blijven werken, maar worden pas naar het nieuwe formaat omgezet als je het formulier opnieuw opslaat in de formulierbouwer.',
      epistolaAdminLegacyOverrideComponents: 'Verouderde componenten',
      epistolaAdminNoLegacyOverride:
        'Geen formulieren met het verouderde override-formaat gevonden.',
    },
    en: {
      title: 'Epistola Document Suite',
      description: 'Document generation using Epistola',
      configurationTitle: 'Configuration name',
      baseUrl: 'Base URL',
      baseUrlTooltip: 'The base URL of the Epistola API (e.g. https://api.epistola.app)',
      apiKey: 'API Key',
      apiKeyTooltip: 'The API key for authentication with Epistola',
      tenantId: 'Tenant ID',
      tenantIdTooltip:
        'The tenant slug in Epistola (3-63 chars, lowercase letters, digits and hyphens only, e.g. "my-tenant")',
      defaultEnvironmentId: 'Default Environment',
      defaultEnvironmentIdTooltip:
        'The default environment for document generation (3-30 chars, lowercase letters, digits and hyphens only, e.g. "production")',
      templateSyncEnabled: 'Template sync',
      templateSyncEnabledTooltip:
        'Automatically synchronize template definitions from classpath to Epistola on startup',
      'epistola-generate-document': 'Generate Document',
      catalogId: 'Catalog',
      catalogIdTooltip: 'Select the catalog to choose a template from',
      templateId: 'Template',
      templateIdTooltip: 'Select the template to use for document generation',
      variantId: 'Variant',
      variantIdTooltip: 'Select the template variant',
      variantSelectionMode: 'Variant selection',
      variantSelectionModeTooltip: 'Choose how the variant is selected',
      selectByVariant: 'Select variant',
      selectByAttributes: 'Select by attributes',
      variantAttributes: 'Variant attributes',
      variantAttributesTooltip:
        'Attributes for automatic variant selection. Values can be expressions (doc:, pv:, case:).',
      attributeKey: 'Attribute',
      attributeKeyCustom: 'Custom...',
      attributeValue: 'Value',
      addAttribute: 'Add attribute',
      removeAttribute: 'Remove attribute',
      attributeRequired: 'Required',
      attributePreferred: 'Preferred',
      environmentId: 'Environment',
      environmentIdTooltip: 'Select the target environment (optional)',
      correlationId: 'Correlation ID',
      correlationIdTooltip: 'An optional correlation ID for tracking this request',
      dataMapping: 'Data Mapping',
      dataMappingTooltip: 'Mapping of template fields to data sources (doc:, pv:, case:)',
      outputFormat: 'Output Format',
      outputFormatTooltip: 'The desired format of the generated document',
      filename: 'Filename',
      filenameTooltip: 'The filename for the generated document',
      resultProcessVariable: 'Result Process Variable',
      resultProcessVariableTooltip: 'The name of the process variable to store the request ID in',
      resultProcessVariableInvalid: 'Use only letters and numbers (A-Z, a-z, 0-9).',
      pdf: 'PDF',
      html: 'HTML',
      // Data mapping builder translations
      dataMappingTitle: 'Data Mapping',
      dataMappingDescription: 'Map template fields to Valtimo data sources',
      mappingModeSimple: 'Simple',
      mappingModeAdvanced: 'Advanced',
      mappingTools: 'Schema & Preview',
      expectedStructure: 'Expected schema',
      expectedStructureLoading: 'Loading schema...',
      previewTitle: 'Preview',
      previewDocPlaceholder: 'Document ID',
      previewExpected: 'Expected',
      previewProduced: 'Produced',
      previewRunHint: 'Enter a document ID and click ▶ to preview the output',
      previewMissing: 'Missing required fields',
      jsonataDescription:
        'JSONata expression that generates the template data. Use $doc, $pv and $case to access document, process variable and case data.',
      templateField: 'Template field',
      dataSource: 'Data source',
      addMapping: 'Add mapping',
      noMappings: 'No mappings added yet. Click "Add mapping" to start.',
      documentFields: 'Document fields',
      processVariables: 'Process variables',
      caseProperties: 'Case properties',
      sourceType: 'Source type',
      sourceTypeDocument: 'Document field',
      sourceTypeProcessVariable: 'Process variable',
      sourceTypeManual: 'Manual value',
      requiredFieldsMissing: 'Not all required fields are mapped',
      requiredFieldsComplete: 'All required fields are mapped',
      validationSummary: 'required fields mapped',
      jsonataValidationErrorsHeading: 'Invalid JSONata expressions:',
      fieldRequired: 'Required',
      fieldOptional: 'Optional',
      mapCollectionTo: 'Map collection to',
      browseMode: 'Browse mode',
      pvMode: 'Process variable mode',
      pvPlaceholder: 'Process variable name',
      expressionMode: 'Expression mode',
      availableFunctions: 'Available functions',
      itemFieldMapping: 'Map field names per item',
      itemFieldMappingTitle: 'Item field mapping:',
      sourceFieldPlaceholder: 'Source field name',
      noTemplateFields: 'No template fields available',
      // Check job status action
      'epistola-check-job-status': 'Check Job Status',
      requestIdVariable: 'Request ID Variable',
      requestIdVariableTooltip: 'Name of the process variable containing the Epistola request ID',
      statusVariable: 'Status Variable',
      statusVariableTooltip: 'Name of the process variable to store the status in',
      documentIdVariable: 'Document ID Variable',
      documentIdVariableTooltip:
        'Name of the process variable to store the document ID in (when completed)',
      errorMessageVariable: 'Error Message Variable',
      errorMessageVariableTooltip:
        'Name of the process variable to store the error message in (when failed)',
      // Download document action
      'epistola-download-document': 'Download Document',
      documentVariable: 'Document Variable',
      documentVariableTooltip:
        'Name of the process variable holding the Epistola result. May be a String document id (legacy) or a rich result object with a `documentId` key — the action extracts the document id from it.',
      storageTarget: 'Storage Target',
      storageTargetTooltip:
        'Where the downloaded PDF is stored. "Temporary resource storage" (recommended) writes only a resource id to the variable — ready to hand to documenten-api:store-temp-document. "Process variable" writes the raw bytes inline (small, non-sensitive documents only; they are included in the task response).',
      resourceIdVariable: 'Resource ID Variable',
      resourceIdVariableTooltip:
        'Name of the process variable to store the temporary resource id in (hand this to documenten-api:store-temp-document).',
      contentVariable: 'Content Variable',
      contentVariableTooltip:
        'Name of the process variable to store the raw PDF bytes inline in (small, non-sensitive documents only).',
      // Admin page
      epistolaAdminOverview: 'Overview',
      epistolaAdminRefresh: 'Refresh',
      epistolaAdminLoading: 'Loading...',
      epistolaAdminNoConfigurations: 'No Epistola plugin configurations found.',
      epistolaAdminTenantId: 'Tenant ID',
      epistolaAdminStatus: 'Status',
      epistolaAdminConnected: 'Connected',
      epistolaAdminUnreachable: 'Unreachable',
      epistolaAdminError: 'Error',
      epistolaAdminPluginActions: 'Plugin actions',
      epistolaAdminProblems: 'Problems',
      epistolaAdminBackToOverview: 'Back to overview',
      epistolaAdminNoUsageForConfig: 'No process actions configured for this connection.',
      epistolaAdminCase: 'Case',
      epistolaAdminProcess: 'Process',
      epistolaAdminActivity: 'Activity',
      epistolaAdminAction: 'Action',
      epistolaAdminServerVersion: 'Server version',
      epistolaAdminContractVersion: 'Contract version',
      epistolaAdminContractPluginVersion: 'Plugin contract',
      epistolaAdminContractServerVersion: 'Server contract',
      epistolaAdminContractCompatibility: 'Contract compatibility',
      epistolaAdminContractWarning: 'Warning',
      epistolaAdminContractUnknown: 'Version not reported',
      epistolaAdminContractError: 'Incompatible',
      epistolaAdminContractWarningBody:
        'The Epistola server is on an older minor contract version than this plugin. The suite is usually backwards compatible, but check the release notes before continuing.',
      epistolaAdminContractUnknownBody:
        'The Epistola server does not report a contract version. Compatibility cannot be checked; upgrade or redeploy the server with a contract build that publishes version metadata.',
      epistolaAdminContractErrorBody:
        'The Epistola server and this plugin use different major contract versions. This can break document generation and catalog deployment; use compatible versions.',
      epistolaAdminExport: 'Export',
      epistolaAdminPendingJobs: 'Pending jobs',
      epistolaAdminNoPendingJobs: 'No pending jobs for this connection.',
      epistolaAdminConfiguration: 'Configuration',
      epistolaAdminRequestId: 'Request ID',
      epistolaAdminReconcile: 'Reconcile',
      epistolaAdminReconciling: 'Reconciling...',
      epistolaAdminReconcileTooltip:
        "Ask Epistola for this job's current status and resume the waiting process if it has finished.",
      epistolaAdminStatusWaiting: 'Waiting',
      epistolaAdminStatusUnwired: 'Stuck',
      epistolaAdminUnwiredTooltip:
        'This wait has no correlation token (epistolaWaitFor), so the collector can never resume it — the process is stuck. Usually an ambiguous merged catch event (see BPMN validation). Fix it in the process model; reconcile cannot recover it.',
      epistolaAdminUnwiredHint: 'Cannot reconcile',
      epistolaAdminConfigurations: 'Configurations',
      epistolaAdminValidations: 'BPMN validation',
      epistolaAdminNoValidations:
        'No race-unsafe process definitions detected. Everything looks good.',
      epistolaAdminValidationWarningTitle: 'BPMN configuration warning',
      epistolaAdminValidationWarningBody:
        'These process definitions have a non-synchronous boundary between the generate-document service task and the EpistolaDocumentGenerated catch event. Results can be missed; use the Reconcile button on the Pending Jobs tab to recover.',
      epistolaAdminValidationCode: 'Code',
      epistolaAdminValidationMessage: 'Message',
      epistolaAdminValidationLastChecked: 'Last checked',
      epistolaAdminValidationNotYetRun: 'not yet run',
      epistolaAdminValidationAutoRefresh: 'automatically re-checked roughly every',
      epistolaAdminValidationLatestVersionNote:
        "Only the latest deployed version of each process definition is checked; older versions with running instances may have problems that aren't shown here.",
      epistolaAdminCatalogs: 'Catalogs',
      epistolaAdminCatalogsIntro:
        'Catalogs on the application classpath. These are deployed automatically on startup; use Redeploy to manually (force) push one to this Epistola installation — for example when the automatic startup deploy failed.',
      epistolaAdminNoCatalogs: 'No catalogs found on the classpath.',
      epistolaAdminCatalogSlug: 'Slug',
      epistolaAdminCatalogVersion: 'Version',
      epistolaAdminCatalogStatus: 'Status in Epistola',
      epistolaAdminCatalogInEpistola: 'Present in Epistola',
      epistolaAdminCatalogNotInEpistola: 'Not in Epistola',
      epistolaAdminCatalogStatusUnknown: 'Unknown (Epistola unreachable)',
      epistolaAdminRedeploy: 'Redeploy',
      epistolaAdminRedeploying: 'Redeploying...',
      epistolaAdminRedeployTooltip:
        'Rebuild this catalog from the classpath and force-push it to Epistola, regardless of version or the templateSyncEnabled setting.',
      epistolaAdminChangelog: 'Changelog',
      epistolaAdminRunningVersion: 'Running plugin version:',
      epistolaAdminNoChangelog: 'No changelog available in this build.',
      // TEMPORARY (remove in 1.0.0): task-id carrier repair (admin "Forms" tab)
      epistolaAdminForms: 'Forms',
      epistolaAdminFormsIntro:
        'Forms with an Epistola component that is missing the hidden task-id field. Without it, preview/download/retry do not work in every task-open flow. Repair adds the field.',
      epistolaAdminFormName: 'Form',
      epistolaAdminFormMissing: 'Missing components',
      epistolaAdminFormReadOnly: 'Read-only',
      epistolaAdminFormReadOnlyHint:
        'This form is deployed from the classpath and is reconciled to its source on the next restart. Add the field to the source (re-drop the component) for a permanent fix.',
      epistolaAdminRepair: 'Repair',
      epistolaAdminRepairAll: 'Repair all',
      epistolaAdminRepairing: 'Working...',
      epistolaAdminRepairTooltip:
        'Add the hidden task-id field to all Epistola components in this form.',
      epistolaAdminNoFormIssues: 'No forms with a missing task-id field found.',
      // TEMPORARY: legacy override-mapping format detection (admin "Forms" tab)
      epistolaAdminLegacyOverrideTitle: 'Legacy input-override format',
      epistolaAdminLegacyOverrideIntro:
        'Forms whose document-preview component still stores input overrides as an object ("form:" references) instead of a JSONata expression over $form. They keep working, but only migrate to the new format once you re-save the form in the form builder.',
      epistolaAdminLegacyOverrideComponents: 'Legacy components',
      epistolaAdminNoLegacyOverride: 'No forms using the legacy override format found.',
    },
  },
};

export { epistolaPluginSpecification };
