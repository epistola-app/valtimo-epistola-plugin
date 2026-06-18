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
  /**
   * Where the downloaded PDF is materialized (see
   * `docs/adr/0001-download-document-content-storage.md`). Defaults to `TEMPORARY_RESOURCE`. The
   * output variable used depends on this choice: `resourceIdVariable` or `contentVariable`.
   */
  storageTarget?: 'TEMPORARY_RESOURCE' | 'PROCESS_VARIABLE';
  /**
   * Output for `TEMPORARY_RESOURCE`: the process variable that receives the temporary resource id
   * (ready to hand to `documenten-api:store-temp-document`).
   */
  resourceIdVariable?: string;
  /**
   * Output for `PROCESS_VARIABLE`: the process variable that receives the raw PDF bytes inline
   * (best for small, non-sensitive documents).
   */
  contentVariable?: string;
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
