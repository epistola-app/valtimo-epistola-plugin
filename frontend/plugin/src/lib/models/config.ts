import {PluginConfigurationData} from '@valtimo/plugin';

/**
 * Plugin-level configuration for Epistola.
 * Contains the tenant ID where document templates are stored.
 */
export interface EpistolaPluginConfig extends PluginConfigurationData {
  tenantId: string;
}

/**
 * Action configuration for the generate-document action.
 * Contains all parameters needed to generate a document.
 */
export interface GenerateDocumentConfig {
  templateId: string;
  dataMapping: Record<string, string>;
  outputFormat: 'PDF' | 'HTML';
  filename: string;
  resultProcessVariable: string;
}
