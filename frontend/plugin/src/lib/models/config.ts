import { PluginConfigurationData } from '@valtimo/plugin';

/**
 * Plugin-level configuration for Epistola.
 * Contains connection settings and defaults.
 */
export interface EpistolaPluginConfig extends PluginConfigurationData {
  baseUrl: string;
  apiKey: string;
  tenantId: string;
  defaultEnvironmentId?: string;
  templateSyncEnabled?: boolean;
}

/**
 * A single variant attribute entry for attribute-based variant selection.
 * When required is true, the variant MUST match this attribute.
 * When required is false, it is a preference (preferred but not mandatory).
 */
export interface VariantAttributeEntry {
  key: string;
  value: string;
  required: boolean;
}

/**
 * Action configuration for the generate-document action.
 * Contains all parameters needed to generate a document.
 *
 * Variant selection supports two modes:
 * - Explicit: set variantId directly
 * - By attributes: set variantAttributes with key-value pairs (values can be JSONata expressions)
 */
export interface GenerateDocumentConfig {
  catalogId: string;
  templateId: string;
  variantId?: string;
  variantAttributes?: VariantAttributeEntry[];
  environmentId?: string;
  dataMapping: string;
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
  /**
   * Name of the process variable that holds the result. May be a plain
   * String document id (legacy) or a `Map<String, Object>` rich result with
   * a `documentId` key (canonical, written by `generate-document` and
   * updated by the result collector). The action extracts the document id.
   */
  documentVariable: string;
  contentVariable: string;
}

export interface VariableSuggestions {
  doc: string[];
  pv: string[];
}

export interface EvaluationResult {
  success: boolean;
  result: Record<string, any> | null;
  error: string | null;
}

/**
 * Request body for the JSONata save-time validation endpoint.
 * All fields are optional; null/blank values are skipped.
 */
export interface ValidateJsonataRequest {
  dataMapping?: string | null;
  filename?: string | null;
  variantId?: string | null;
  variantAttributeValues?: Record<string, string> | null;
}

export interface JsonataFieldError {
  field: string;
  expression: string;
  message: string;
}

export interface JsonataValidationResult {
  valid: boolean;
  errors: JsonataFieldError[];
}
