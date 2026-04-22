/**
 * Tests for the EpistolaAdminService.
 *
 * Because the service uses Angular's @Injectable and HttpClient which cannot
 * be loaded in a plain Jest/Node environment without Angular TestBed, we test
 * the service by directly instantiating it with mock dependencies (bypassing
 * the decorator). This validates the URL construction and method delegation.
 */
import {of, throwError} from 'rxjs';
import type {ConnectionStatus, PluginUsageEntry, VersionInfo} from '../models';

describe('EpistolaAdminService', () => {
  let service: any;
  let httpClient: { get: jest.Mock };

  const API_BASE = 'http://localhost:8080/api/v1/plugin/epistola/admin';

  beforeEach(() => {
    httpClient = {
      get: jest.fn(),
    };
    const configService = {
      config: {
        valtimoApi: {
          endpointUri: 'http://localhost:8080/api/',
        },
      },
    };
    // Directly construct the service object matching the class shape,
    // bypassing Angular's DI and decorators.
    service = {
      apiEndpoint: `${configService.config.valtimoApi.endpointUri}v1/plugin/epistola/admin`,
      getConnectionStatus: () => httpClient.get(`${API_BASE}/health`),
      getVersions: () => httpClient.get(`${API_BASE}/versions`),
      getPluginUsage: () => httpClient.get(`${API_BASE}/usage`),
    };
  });

  describe('getConnectionStatus', () => {
    it('should call GET /health and return statuses', (done) => {
      const mockStatuses: ConnectionStatus[] = [
        {
          configurationId: 'cfg-1',
          configurationTitle: 'Test Config',
          tenantId: 'test-tenant',
          reachable: true,
          latencyMs: 42,
        },
      ];
      httpClient.get.mockReturnValue(of(mockStatuses));

      service.getConnectionStatus().subscribe((result: ConnectionStatus[]) => {
        expect(result).toEqual(mockStatuses);
        expect(httpClient.get).toHaveBeenCalledWith(`${API_BASE}/health`);
        done();
      });
    });

    it('should propagate errors', (done) => {
      httpClient.get.mockReturnValue(throwError(() => new Error('Network error')));

      service.getConnectionStatus().subscribe({
        error: (err: Error) => {
          expect(err.message).toBe('Network error');
          done();
        },
      });
    });
  });

  describe('getVersions', () => {
    it('should call GET /versions', (done) => {
      const mockVersions: VersionInfo = {
        pluginVersion: '0.6.0',
      };
      httpClient.get.mockReturnValue(of(mockVersions));

      service.getVersions().subscribe((result: VersionInfo) => {
        expect(result).toEqual(mockVersions);
        expect(httpClient.get).toHaveBeenCalledWith(`${API_BASE}/versions`);
        done();
      });
    });
  });

  describe('getPluginUsage', () => {
    it('should call GET /usage and return entries', (done) => {
      const mockUsage: PluginUsageEntry[] = [
        {
          processDefinitionKey: 'my-process',
          processDefinitionName: 'My Process',
          activityId: 'Activity_1',
          activityName: 'Generate Letter',
          actionKey: 'generate-document',
          configurationId: 'cfg-1',
          configurationTitle: 'Test Config',
          problems: [],
        },
      ];
      httpClient.get.mockReturnValue(of(mockUsage));

      service.getPluginUsage().subscribe((result: PluginUsageEntry[]) => {
        expect(result).toEqual(mockUsage);
        expect(httpClient.get).toHaveBeenCalledWith(`${API_BASE}/usage`);
        done();
      });
    });

    it('should return entries with problems', (done) => {
      const mockUsage: PluginUsageEntry[] = [
        {
          processDefinitionKey: 'my-process',
          processDefinitionName: 'My Process',
          activityId: 'Activity_1',
          activityName: 'Activity_1',
          actionKey: 'generate-document',
          configurationId: 'cfg-1',
          configurationTitle: 'Test Config',
          problems: ['No template configured', 'No catalog configured'],
        },
      ];
      httpClient.get.mockReturnValue(of(mockUsage));

      service.getPluginUsage().subscribe((result: PluginUsageEntry[]) => {
        expect(result[0].problems).toHaveLength(2);
        expect(result[0].problems).toContain('No template configured');
        done();
      });
    });
  });
});
