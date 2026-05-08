import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@valtimo/shared';
import { Observable } from 'rxjs';
import {
  AttributeDefinition,
  CatalogInfo,
  EnvironmentInfo,
  ExpressionFunctionInfo,
  JsonataValidationResult,
  TemplateDetails,
  TemplateInfo,
  EvaluationResult,
  ValidateJsonataRequest,
  VariableSuggestions,
  VariantInfo,
} from '../models';

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
   * @param taskId Operaton user task id (required — backend authorizes via OperatonTask:VIEW)
   */
  getRetryForm(
    taskId: string,
    processInstanceId: string,
    documentId?: string,
    sourceActivityId?: string,
  ): Observable<any> {
    const params: Record<string, string> = { taskId, processInstanceId };
    if (documentId) {
      params['documentId'] = documentId;
    }
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
   * Preview a document by dry-running the generate-document process link.
   *
   * @param taskId Operaton user task id (required — backend authorizes via OperatonTask:VIEW)
   */
  previewDocument(
    taskId: string,
    documentId: string,
    processDefinitionKey: string,
    sourceActivityId: string,
    processInstanceId?: string,
    overrides?: Record<string, any>,
  ): Observable<any> {
    return this.http.post<any>(`${this.apiEndpoint}/preview`, {
      taskId,
      documentId,
      processDefinitionKey,
      sourceActivityId,
      processInstanceId: processInstanceId || null,
      overrides: overrides || null,
    });
  }
}
