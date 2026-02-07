import {PluginConfigurationData} from '@valtimo/plugin';

/**
 * Plugin-level configuration for Epistola.
 * Contains connection settings and defaults.
 */
export interface EpistolaPluginConfig extends PluginConfigurationData {
  baseUrl: string;
  apiKey: string;
  tenantId: string;
  defaultEnvironmentId?: string;
}

/**
 * Action configuration for the generate-document action.
 * Contains all parameters needed to generate a document.
 */
export interface GenerateDocumentConfig {
  templateId: string;
  variantId: string;
  environmentId?: string;
  dataMapping: Record<string, string>;
  outputFormat: 'PDF' | 'HTML';
  filename: string;
  correlationId?: string;
  resultProcessVariable: string;
}
