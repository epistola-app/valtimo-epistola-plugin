import {PluginSpecification} from '@valtimo/plugin';
import {EpistolaConfigurationComponent} from './components/epistola-configuration/epistola-configuration.component';
import {
  GenerateDocumentConfigurationComponent
} from './components/generate-document-configuration/generate-document-configuration.component';
import {EPISTOLA_PLUGIN_LOGO_BASE64} from './assets';

const epistolaPluginSpecification: PluginSpecification = {
  // Must match backend @Plugin(key = "epistola")
  pluginId: 'epistola',

  // Component for plugin-level configuration (tenantId)
  pluginConfigurationComponent: EpistolaConfigurationComponent,

  // Plugin logo
  pluginLogoBase64: EPISTOLA_PLUGIN_LOGO_BASE64,

  // Map action keys to their configuration components
  functionConfigurationComponents: {
    'generate-document': GenerateDocumentConfigurationComponent,
  },

  // Translations
  pluginTranslations: {
    nl: {
      title: 'Epistola Document Suite',
      description: 'Documentgeneratie met Epistola',
      configurationTitle: 'Configuratienaam',
      tenantId: 'Tenant ID',
      tenantIdTooltip: 'De tenant ID waar de document templates zijn opgeslagen in Epistola',
      'generate-document': 'Genereer Document',
      templateId: 'Template',
      templateIdTooltip: 'Selecteer het template dat gebruikt wordt voor documentgeneratie',
      dataMapping: 'Data Mapping',
      dataMappingTooltip: 'Koppeling van template velden naar data bronnen (doc:, pv:, case:)',
      outputFormat: 'Uitvoerformaat',
      outputFormatTooltip: 'Het gewenste formaat van het gegenereerde document',
      filename: 'Bestandsnaam',
      filenameTooltip: 'De bestandsnaam voor het gegenereerde document',
      resultProcessVariable: 'Resultaat Procesvariabele',
      resultProcessVariableTooltip: 'De naam van de procesvariabele waarin het request ID wordt opgeslagen',
      pdf: 'PDF',
      html: 'HTML',
      // Data mapping builder translations
      dataMappingTitle: 'Data Mapping',
      dataMappingDescription: 'Koppel template velden aan Valtimo data bronnen',
      templateField: 'Template veld',
      dataSource: 'Data bron',
      addMapping: 'Mapping toevoegen',
      noMappings: 'Nog geen mappings toegevoegd. Klik op "Mapping toevoegen" om te beginnen.',
      documentFields: 'Document velden',
      processVariables: 'Procesvariabelen',
      caseProperties: 'Zaak eigenschappen'
    },
    en: {
      title: 'Epistola Document Suite',
      description: 'Document generation using Epistola',
      configurationTitle: 'Configuration name',
      tenantId: 'Tenant ID',
      tenantIdTooltip: 'The tenant ID where document templates are stored in Epistola',
      'generate-document': 'Generate Document',
      templateId: 'Template',
      templateIdTooltip: 'Select the template to use for document generation',
      dataMapping: 'Data Mapping',
      dataMappingTooltip: 'Mapping of template fields to data sources (doc:, pv:, case:)',
      outputFormat: 'Output Format',
      outputFormatTooltip: 'The desired format of the generated document',
      filename: 'Filename',
      filenameTooltip: 'The filename for the generated document',
      resultProcessVariable: 'Result Process Variable',
      resultProcessVariableTooltip: 'The name of the process variable to store the request ID in',
      pdf: 'PDF',
      html: 'HTML',
      // Data mapping builder translations
      dataMappingTitle: 'Data Mapping',
      dataMappingDescription: 'Map template fields to Valtimo data sources',
      templateField: 'Template field',
      dataSource: 'Data source',
      addMapping: 'Add mapping',
      noMappings: 'No mappings added yet. Click "Add mapping" to start.',
      documentFields: 'Document fields',
      processVariables: 'Process variables',
      caseProperties: 'Case properties'
    }
  }
};

export {epistolaPluginSpecification};
