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
 *
 * Variant selection supports two modes:
 * - Explicit: set variantId directly
 * - By attributes: set variantAttributes with key-value pairs (values can be value resolver expressions)
 */
export interface GenerateDocumentConfig {
  templateId: string;
  variantId?: string;
  variantAttributes?: Record<string, string>;
  environmentId?: string;
  dataMapping: Record<string, any>;
  outputFormat: 'PDF' | 'HTML';
  filename: string;
  correlationId?: string;
  resultProcessVariable: string;
}

/**
 * Action configuration for the check-job-status action.
 * Specifies which process variables to read from and write to.
 */
export interface CheckJobStatusConfig {
  requestIdVariable: string;
  statusVariable: string;
  documentIdVariable?: string;
  errorMessageVariable?: string;
}

/**
 * Action configuration for the download-document action.
 * Specifies which process variables to read from and write to.
 */
export interface DownloadDocumentConfig {
  documentIdVariable: string;
  contentVariable: string;
}
