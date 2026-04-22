import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {ConfigService} from '@valtimo/shared';
import {Observable} from 'rxjs';
import {ConnectionStatus, PluginUsageEntry, VersionInfo} from '../models';

/**
 * Service for Epistola plugin administrative operations.
 * Provides health checks, version info, and usage overview.
 */
@Injectable()
export class EpistolaAdminService {
  private readonly apiEndpoint: string;

  constructor(
    private readonly http: HttpClient,
    private readonly configService: ConfigService
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola/admin`;
  }

  /**
   * Check connectivity to Epistola for all plugin configurations.
   */
  getConnectionStatus(): Observable<ConnectionStatus[]> {
    return this.http.get<ConnectionStatus[]>(`${this.apiEndpoint}/health`);
  }

  /**
   * Get version information for the plugin and connected Epistola server.
   */
  getVersions(): Observable<VersionInfo> {
    return this.http.get<VersionInfo>(`${this.apiEndpoint}/versions`);
  }

  /**
   * Get an overview of all Epistola plugin usages across process definitions.
   */
  getPluginUsage(): Observable<PluginUsageEntry[]> {
    return this.http.get<PluginUsageEntry[]>(`${this.apiEndpoint}/usage`);
  }
}
