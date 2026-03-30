import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {ConfigService} from '@valtimo/shared';
import {Observable} from 'rxjs';
import {EnvironmentInfo, PreviewSource, TemplateDetails, TemplateInfo, ValidationResult, VariantInfo} from '../models';

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
    private readonly configService: ConfigService
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
  }

  /**
   * Get all available templates for a plugin configuration.
   */
  getTemplates(pluginConfigurationId: string): Observable<TemplateInfo[]> {
    return this.http.get<TemplateInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates`
    );
  }

  /**
   * Get template details including its fields.
   */
  getTemplateDetails(pluginConfigurationId: string, templateId: string): Observable<TemplateDetails> {
    return this.http.get<TemplateDetails>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}`
    );
  }

  /**
   * Get all available environments for a plugin configuration.
   */
  getEnvironments(pluginConfigurationId: string): Observable<EnvironmentInfo[]> {
    return this.http.get<EnvironmentInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/environments`
    );
  }

  /**
   * Get all variants for a specific template.
   */
  getVariants(pluginConfigurationId: string, templateId: string): Observable<VariantInfo[]> {
    return this.http.get<VariantInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}/variants`
    );
  }

  /**
   * Discover process variable names for a given process definition.
   */
  getProcessVariables(processDefinitionKey: string): Observable<string[]> {
    return this.http.get<string[]>(
      `${this.apiEndpoint}/process-variables`,
      {params: {processDefinitionKey}}
    );
  }

  /**
   * Validate that a data mapping covers all required template fields.
   */
  validateMapping(
    pluginConfigurationId: string,
    templateId: string,
    dataMapping: Record<string, any>
  ): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}/validate-mapping`,
      {dataMapping}
    );
  }

  /**
   * Get a dynamically generated Formio form for retrying a failed document generation.
   */
  getRetryForm(
    processInstanceId: string,
    documentId?: string,
    sourceActivityId?: string
  ): Observable<any> {
    const params: Record<string, string> = {processInstanceId};
    if (documentId) {
      params['documentId'] = documentId;
    }
    if (sourceActivityId) {
      params['sourceActivityId'] = sourceActivityId;
    }
    return this.http.get<any>(`${this.apiEndpoint}/retry-form`, {params});
  }

  /**
   * Discover all previewable document sources for a given Valtimo document.
   */
  getPreviewSources(documentId: string): Observable<PreviewSource[]> {
    return this.http.get<PreviewSource[]>(
      `${this.apiEndpoint}/preview-sources`,
      {params: {documentId}}
    );
  }

  /**
   * Preview a document by dry-running the generate-document process link.
   * Returns the resolved data as a mock preview (Phase 1).
   */
  previewDocument(
    documentId: string,
    processDefinitionKey: string,
    sourceActivityId: string,
    processInstanceId?: string,
    overrides?: Record<string, any>
  ): Observable<any> {
    return this.http.post<any>(`${this.apiEndpoint}/preview`, {
      documentId,
      processDefinitionKey,
      sourceActivityId,
      processInstanceId: processInstanceId || null,
      overrides: overrides || null
    });
  }
}
