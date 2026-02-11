import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {ConfigService} from '@valtimo/shared';
import {Observable} from 'rxjs';
import {EnvironmentInfo, TemplateDetails, TemplateInfo, ValidationResult, VariantInfo} from '../models';

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
    dataMapping: Record<string, string>
  ): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}/validate-mapping`,
      {dataMapping}
    );
  }
}
