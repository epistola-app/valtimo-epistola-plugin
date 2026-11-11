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
import { Observable, map } from 'rxjs';
import {
  AttributeDefinition,
  CatalogInfo,
  CatalogTemplates,
  DesignScope,
  ReadSelection,
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
import { ANY_ENVIRONMENT, ANY_RANGE, EnvironmentScope, RangeScope } from './release-scope';

export { ANY_ENVIRONMENT, ANY_RANGE } from './release-scope';
export type { EnvironmentScope, RangeScope } from './release-scope';

/**
 * Body of a {@link EpistolaPluginService.previewToBlob} call. Mirrors the
 * backend {@code PreviewRequest} record. The backend derives the process
 * instance and case document from the authorized task, so only {@code taskId}
 * and {@code sourceActivityId} (which identifies the {@code generate-document}
 * process link) are sent; {@code inputOverrides} lets the caller substitute data
 * before the JSONata mapping runs.
 */
export interface PreviewBlobRequest {
  taskId: string;
  sourceActivityId?: string | null;
  inputOverrides?: Record<string, unknown> | null;
}

/**
 * Request body for {@link EpistolaPluginService.previewStartToBlob} — the start-event preview.
 *
 * <p>A start event has no task and no process instance, so this names the process definition
 * instead; the backend authorizes the caller's permission to start it and resolves the key to a
 * definition id itself. The key rather than the id is on the wire because it is version-stable —
 * a version-pinned id would break every saved form on the next BPMN deployment.
 *
 * <p>Carries no {@code taskId}: the endpoint rejects a body containing one outright rather than
 * ignoring it, so the two modes cannot be conflated.
 */
export interface StartPreviewBlobRequest {
  processDefinitionKey: string;
  sourceActivityId: string;
  /** Only for the start-a-process-on-an-existing-case flavour; null for a brand-new case. */
  documentId?: string | null;
  inputOverrides?: Record<string, unknown> | null;
}

/**
 * Query-string parameters for {@link EpistolaPluginService.downloadDocumentBlob}.
 * The backend resolves the Epistola PDF id and tenant id from the named process
 * variables on the caller's task — no raw PDF id is sent on the wire.
 */
export interface DownloadDocumentRequest {
  taskId: string;
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
function environmentParams(environment: EnvironmentScope): Record<string, string> {
  if (environment === ANY_ENVIRONMENT) return { anyEnvironment: 'true' };
  return environment ? { environmentId: environment } : {};
}

function rangeParams(range: RangeScope): Record<string, string> {
  if (range === ANY_RANGE) return { anyRange: 'true' };
  const trimmed = range?.trim();
  return trimmed ? { versionRange: trimmed } : {};
}

/**
 * A design scope's selection as a lookup sends it. A catalog-wide read must say so: sent without
 * an environment, the server would put the configuration's default environment in its place, which
 * is exactly where the catalog may not be deployed.
 */
function readParams(read: ReadSelection): Record<string, string> {
  const params: Record<string, string> = read.environmentId
    ? { environmentId: read.environmentId }
    : { anyEnvironment: 'true' };
  if (read.versionRange) params['versionRange'] = read.versionRange;
  return params;
}

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
   * The tenant a plugin configuration connects to, or null when it cannot be read.
   */
  getTenantId(pluginConfigurationId: string): Observable<string | null> {
    return this.http
      .get<{ id: string; tenantId: string | null }[]>(`${this.apiEndpoint}/configurations`)
      .pipe(
        map(
          (configurations) =>
            configurations.find((configuration) => configuration.id === pluginConfigurationId)
              ?.tenantId ?? null,
        ),
      );
  }

  /**
   * Every catalog of a plugin configuration's tenant, each marked whether it is deployed to the
   * environment (`deployedToEnvironment`); not narrowed to it.
   */
  getCatalogs(
    pluginConfigurationId: string,
    environment?: EnvironmentScope,
  ): Observable<CatalogInfo[]> {
    return this.http.get<CatalogInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/catalogs`,
      { params: environmentParams(environment) },
    );
  }

  /**
   * Get all available templates for a plugin configuration and catalog.
   */
  getTemplates(
    pluginConfigurationId: string,
    catalogId: string,
    read: ReadSelection,
  ): Observable<TemplateInfo[]> {
    const params: Record<string, string> = { ...readParams(read), catalogId };
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
    read: ReadSelection,
  ): Observable<TemplateDetails> {
    return this.http.get<TemplateDetails>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}`,
      { params: { ...readParams(read), catalogId } },
    );
  }

  /**
   * Get all attribute definitions for a plugin configuration's tenant and catalog.
   */
  getAttributes(
    pluginConfigurationId: string,
    catalogId: string,
    read: ReadSelection,
  ): Observable<AttributeDefinition[]> {
    const params: Record<string, string> = { ...readParams(read), catalogId };
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
    read: ReadSelection,
  ): Observable<VariantInfo[]> {
    const params: Record<string, string> = { ...readParams(read), catalogId };
    return this.http.get<VariantInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}/variants`,
      { params },
    );
  }

  /**
   * The release a configurator designs a link against, resolved once: every further lookup sends
   * its `read`. Falls back where the link would render nothing and says why in `problems`; answers
   * 400 when a literal range is not one (ADR 0009).
   */
  getDesignScope(
    pluginConfigurationId: string,
    catalogId: string,
    environment: EnvironmentScope,
    range: RangeScope,
  ): Observable<DesignScope> {
    return this.http.get<DesignScope>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/design-scope`,
      { params: { ...environmentParams(environment), ...rangeParams(range), catalogId } },
    );
  }

  /**
   * Every template in the releases of a catalog the link can reach, with the releases each is in;
   * for a link whose release (or environment) is an expression.
   */
  getCatalogTemplates(
    pluginConfigurationId: string,
    catalogId: string,
    environment: EnvironmentScope,
  ): Observable<CatalogTemplates> {
    return this.http.get<CatalogTemplates>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/catalog-templates`,
      { params: { ...environmentParams(environment), catalogId } },
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
   * Render the preview for a BPMN start form, where no process instance exists yet.
   *
   * <p>A separate endpoint from {@link previewToBlob}, not a variant of it: the two carry different
   * authorization models ({@code OperatonTask:VIEW} on a task versus permission to start a process),
   * and keeping them apart means neither can be reached from the other's call site.
   *
   * <p>Same {@code X-Skip-Interceptor: 422} treatment, since both share the RENDER_FAILED mapping.
   */
  previewStartToBlob(request: StartPreviewBlobRequest): Observable<Blob> {
    return this.http.post(`${this.apiEndpoint}/preview/start`, request, {
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
    return this.http.get(`${this.apiEndpoint}/documents/download?${params.toString()}`, {
      responseType: 'blob',
    });
  }
}
