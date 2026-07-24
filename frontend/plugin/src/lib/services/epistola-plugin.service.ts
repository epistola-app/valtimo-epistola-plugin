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

import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { ConfigService } from '@valtimo/shared';
import { Observable } from 'rxjs';
import {
  AttributeDefinition,
  CatalogInfo,
  EnvironmentInfo,
  ExpressionFunctionInfo,
  JsonataValidationResult,
  ProcessLinkMapping,
  TemplateDetails,
  TemplateInfo,
  EvaluationResult,
  ValidateJsonataRequest,
  VariableSuggestions,
  VariantInfo,
} from '../models';

/**
 * Body of a {@link EpistolaPluginService.previewToBlob} call. Mirrors the
 * backend {@code PreviewRequest} record. The backend derives the process
 * instance and case document from the authorized task, so only {@code taskId}
 * and {@code sourceActivityId} (which identifies the {@code generate-document}
 * process link) are sent; {@code overrides} and {@code inputOverrides} let the
 * caller substitute data before the JSONata mapping runs.
 */
export interface PreviewBlobRequest {
  taskId: string;
  sourceActivityId?: string | null;
  inputOverrides?: Record<string, unknown> | null;
  overrides?: Record<string, unknown> | null;
}

/**
 * Query-string parameters for {@link EpistolaPluginService.downloadDocumentBlob}.
 * The backend resolves the Epistola PDF id and tenant id from the named process
 * variables on the caller's task — no raw PDF id is sent on the wire.
 */
export interface DownloadDocumentRequest {
  taskId: string;
  /** @deprecated The backend derives the case from taskId. Kept for older API consumers. */
  caseDocumentId?: string;
  documentVariable: string;
  tenantIdVariable: string;
  filename: string;
  disposition: 'attachment' | 'inline';
}

/**
 * Service for interacting with Epistola plugin API endpoints.
 * Provides methods to fetch templates, environments, variants,
 * process variables, and validate mappings.
 */
@Injectable()
export class EpistolaPluginService {
  private readonly apiEndpoint: string;

  constructor(
    private readonly http: HttpClient,
    private readonly configService: ConfigService,
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
  }

  /**
   * Get all available catalogs for a plugin configuration.
   */
  getCatalogs(pluginConfigurationId: string): Observable<CatalogInfo[]> {
    return this.http.get<CatalogInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/catalogs`,
    );
  }

  /**
   * Get all available templates for a plugin configuration and catalog.
   */
  getTemplates(pluginConfigurationId: string, catalogId?: string): Observable<TemplateInfo[]> {
    const params: Record<string, string> = {};
    if (catalogId) params['catalogId'] = catalogId;
    return this.http.get<TemplateInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates`,
      { params },
    );
  }

  /**
   * Get template details including its fields.
   */
  getTemplateDetails(
    pluginConfigurationId: string,
    templateId: string,
    catalogId: string,
  ): Observable<TemplateDetails> {
    return this.http.get<TemplateDetails>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}`,
      { params: { catalogId } },
    );
  }

  /**
   * Get all attribute definitions for a plugin configuration's tenant and catalog.
   */
  getAttributes(
    pluginConfigurationId: string,
    catalogId: string,
  ): Observable<AttributeDefinition[]> {
    const params: Record<string, string> = {};
    if (catalogId) params['catalogId'] = catalogId;
    return this.http.get<AttributeDefinition[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/attributes`,
      { params },
    );
  }

  /**
   * Get all available environments for a plugin configuration.
   */
  getEnvironments(pluginConfigurationId: string): Observable<EnvironmentInfo[]> {
    return this.http.get<EnvironmentInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/environments`,
    );
  }

  /**
   * Get all variants for a specific template.
   */
  getVariants(
    pluginConfigurationId: string,
    templateId: string,
    catalogId: string,
  ): Observable<VariantInfo[]> {
    const params: Record<string, string> = {};
    if (catalogId) params['catalogId'] = catalogId;
    return this.http.get<VariantInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}/variants`,
      { params },
    );
  }

  /**
   * Discover process variable names for a given process definition.
   */
  getProcessVariables(processDefinitionKey: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiEndpoint}/process-variables`, {
      params: { processDefinitionKey },
    });
  }

  /**
   * Get the raw `dataMapping` JSONata of a generate-document process link, identified by its
   * process definition key and activity id. The override builder extracts the referenced
   * `$doc`/`$pv` paths from it to guide the author. Returns an empty mapping when unresolved.
   */
  getProcessLinkMapping(
    processDefinitionKey: string,
    activityId: string,
  ): Observable<ProcessLinkMapping> {
    return this.http.get<ProcessLinkMapping>(`${this.apiEndpoint}/process-link-mapping`, {
      params: { processDefinitionKey, activityId },
    });
  }

  /**
   * Get variable suggestions for JSONata autocompletion.
   */
  getVariableSuggestions(
    caseDefinitionKey?: string,
    processDefinitionKey?: string,
  ): Observable<VariableSuggestions> {
    const params: Record<string, string> = {};
    if (caseDefinitionKey) params['caseDefinitionKey'] = caseDefinitionKey;
    if (processDefinitionKey) params['processDefinitionKey'] = processDefinitionKey;
    return this.http.get<VariableSuggestions>(`${this.apiEndpoint}/variable-suggestions`, {
      params,
    });
  }

  /**
   * Evaluate a JSONata expression against a real document.
   */
  evaluateMapping(
    expression: string,
    documentId: string,
    processInstanceId?: string,
  ): Observable<EvaluationResult> {
    return this.http.post<EvaluationResult>(`${this.apiEndpoint}/evaluate-mapping`, {
      expression,
      documentId,
      processInstanceId: processInstanceId ?? null,
    });
  }

  /**
   * Get a dynamically generated Formio form for retrying a failed document generation.
   *
   * The backend derives the process instance and case document from the authorized task,
   * so only the task id (and optionally the source activity) is sent.
   *
   * @param taskId Operaton user task id (required — backend authorizes via OperatonTask:VIEW)
   */
  getRetryForm(taskId: string, sourceActivityId?: string): Observable<any> {
    const params: Record<string, string> = { taskId };
    if (sourceActivityId) {
      params['sourceActivityId'] = sourceActivityId;
    }
    return this.http.get<any>(`${this.apiEndpoint}/retry-form`, { params });
  }

  /**
   * List all available expression functions for expr: data mapping values.
   */
  getExpressionFunctions(): Observable<ExpressionFunctionInfo[]> {
    return this.http.get<ExpressionFunctionInfo[]>(`${this.apiEndpoint}/expression-functions`);
  }

  /**
   * Validate the JSONata syntax of action-config expressions before save.
   * Parse-only; runtime errors (missing variables, type mismatches) are not detected.
   */
  validateJsonata(request: ValidateJsonataRequest): Observable<JsonataValidationResult> {
    return this.http.post<JsonataValidationResult>(`${this.apiEndpoint}/validate-jsonata`, request);
  }

  /**
   * Preview a document by dry-running the {@code generate-document} process
   * link. Returns the rendered PDF as a {@link Blob}.
   *
   * <p>The {@code X-Skip-Interceptor: 422} header tells the global Valtimo
   * error interceptor to skip its toast for validation failures so the
   * caller can render an inline error message.
   */
  previewToBlob(request: PreviewBlobRequest): Observable<Blob> {
    return this.http.post(`${this.apiEndpoint}/preview`, request, {
      responseType: 'blob',
      headers: new HttpHeaders().set('X-Skip-Interceptor', '422'),
    });
  }

  /**
   * Stream an already-generated Epistola PDF for the caller's task. The
   * backend resolves the Epistola document id and tenant id from the named
   * process variables on the task's process instance, so the wire never
   * carries a raw PDF id.
   */
  downloadDocumentBlob(request: DownloadDocumentRequest): Observable<Blob> {
    const params = new URLSearchParams({
      taskId: request.taskId,
      documentVariable: request.documentVariable,
      tenantIdVariable: request.tenantIdVariable,
      filename: request.filename,
      disposition: request.disposition,
    });
    if (request.caseDocumentId) {
      params.set('caseDocumentId', request.caseDocumentId);
    }
    return this.http.get(`${this.apiEndpoint}/documents/download?${params.toString()}`, {
      responseType: 'blob',
    });
  }
}
