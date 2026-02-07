import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {ConfigService} from '@valtimo/shared';
import {Observable} from 'rxjs';
import {EnvironmentInfo, TemplateDetails, TemplateInfo, VariantInfo} from '../models';

/**
 * Service for interacting with Epistola plugin API endpoints.
 * Provides methods to fetch templates, environments, and variants.
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
   *
   * @param pluginConfigurationId The plugin configuration ID
   * @returns Observable of template list
   */
  getTemplates(pluginConfigurationId: string): Observable<TemplateInfo[]> {
    return this.http.get<TemplateInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates`
    );
  }

  /**
   * Get template details including its fields.
   *
   * @param pluginConfigurationId The plugin configuration ID
   * @param templateId The template ID
   * @returns Observable of template details
   */
  getTemplateDetails(pluginConfigurationId: string, templateId: string): Observable<TemplateDetails> {
    return this.http.get<TemplateDetails>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}`
    );
  }

  /**
   * Get all available environments for a plugin configuration.
   *
   * @param pluginConfigurationId The plugin configuration ID
   * @returns Observable of environment list
   */
  getEnvironments(pluginConfigurationId: string): Observable<EnvironmentInfo[]> {
    return this.http.get<EnvironmentInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/environments`
    );
  }

  /**
   * Get all variants for a specific template.
   *
   * @param pluginConfigurationId The plugin configuration ID
   * @param templateId The template ID
   * @returns Observable of variant list
   */
  getVariants(pluginConfigurationId: string, templateId: string): Observable<VariantInfo[]> {
    return this.http.get<VariantInfo[]>(
      `${this.apiEndpoint}/configurations/${pluginConfigurationId}/templates/${templateId}/variants`
    );
  }
}
