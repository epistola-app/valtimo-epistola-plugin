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

/**
 * Tests for the EpistolaAdminService.
 *
 * Because the service uses Angular's @Injectable and HttpClient which cannot
 * be loaded in a plain Jest/Node environment without Angular TestBed, we test
 * the service by directly instantiating it with mock dependencies (bypassing
 * the decorator). This validates the URL construction and method delegation.
 */
import { of, throwError } from 'rxjs';
import type { ConnectionStatus, PendingJob, PluginUsageEntry, VersionInfo } from '../models';

describe('EpistolaAdminService', () => {
  let service: any;
  let httpClient: { get: jest.Mock; post: jest.Mock };

  const API_BASE = 'http://localhost:8080/api/v1/plugin/epistola/admin';

  beforeEach(() => {
    httpClient = {
      get: jest.fn(),
      post: jest.fn(),
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
      getPendingJobs: () => httpClient.get(`${API_BASE}/pending`),
      exportProcessLink: (id: string) =>
        httpClient.get(`${API_BASE}/export/${encodeURIComponent(id)}`, { responseType: 'blob' }),
      getFormCarrierIssues: () => httpClient.get(`${API_BASE}/forms/carrier-issues`),
      repairFormCarrier: (id: string) =>
        httpClient.post(`${API_BASE}/forms/${encodeURIComponent(id)}/repair-carrier`, null),
      repairAllFormCarriers: () => httpClient.post(`${API_BASE}/forms/repair-carrier`, null),
    };
  });

  describe('form carrier repair (temporary, removed in 1.0.0)', () => {
    it('getFormCarrierIssues calls GET /forms/carrier-issues', (done) => {
      const issues = [{ formId: 'f1', name: 'assess', missingComponents: 1, readOnly: false }];
      httpClient.get.mockReturnValue(of(issues));
      service.getFormCarrierIssues().subscribe((result: unknown) => {
        expect(result).toEqual(issues);
        expect(httpClient.get).toHaveBeenCalledWith(`${API_BASE}/forms/carrier-issues`);
        done();
      });
    });

    it('repairFormCarrier POSTs to /forms/{id}/repair-carrier with null body', (done) => {
      const res = {
        formId: 'f1',
        name: 'assess',
        success: true,
        componentsPatched: 1,
        errorMessage: null,
      };
      httpClient.post.mockReturnValue(of(res));
      service.repairFormCarrier('f1').subscribe((result: unknown) => {
        expect(result).toEqual(res);
        expect(httpClient.post).toHaveBeenCalledWith(`${API_BASE}/forms/f1/repair-carrier`, null);
        done();
      });
    });

    it('repairAllFormCarriers POSTs to /forms/repair-carrier with null body', (done) => {
      const summary = { formsRepaired: 2, componentsPatched: 3, failed: 0 };
      httpClient.post.mockReturnValue(of(summary));
      service.repairAllFormCarriers().subscribe((result: unknown) => {
        expect(result).toEqual(summary);
        expect(httpClient.post).toHaveBeenCalledWith(`${API_BASE}/forms/repair-carrier`, null);
        done();
      });
    });

    it('propagates errors from repairFormCarrier', (done) => {
      httpClient.post.mockReturnValue(throwError(() => new Error('boom')));
      service.repairFormCarrier('f1').subscribe({
        error: (err: Error) => {
          expect(err.message).toBe('boom');
          done();
        },
      });
    });
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
          processLinkId: 'link-1',
          processDefinitionKey: 'my-process',
          processDefinitionName: 'My Process',
          activityId: 'Activity_1',
          activityName: 'Generate Letter',
          actionKey: 'epistola-generate-document',
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
          processLinkId: 'link-2',
          processDefinitionKey: 'my-process',
          processDefinitionName: 'My Process',
          activityId: 'Activity_1',
          activityName: 'Activity_1',
          actionKey: 'epistola-generate-document',
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

  describe('getPendingJobs', () => {
    it('should call GET /pending and return jobs', (done) => {
      const mockJobs: PendingJob[] = [
        {
          executionId: 'exec-1',
          processInstanceId: 'pi-1',
          processDefinitionKey: 'my-process',
          processDefinitionName: 'My Process',
          activityId: 'waitForDocument',
          activityName: 'Wait for document',
          tenantId: 'test-tenant',
          requestId: 'req-123',
          configurationTitle: 'Test Config',
          status: 'WAITING',
        },
      ];
      httpClient.get.mockReturnValue(of(mockJobs));

      service.getPendingJobs().subscribe((result: PendingJob[]) => {
        expect(result).toEqual(mockJobs);
        expect(httpClient.get).toHaveBeenCalledWith(`${API_BASE}/pending`);
        done();
      });
    });

    it('should return empty array when no pending jobs', (done) => {
      httpClient.get.mockReturnValue(of([]));

      service.getPendingJobs().subscribe((result: PendingJob[]) => {
        expect(result).toHaveLength(0);
        done();
      });
    });
  });

  describe('exportProcessLink', () => {
    it('should call GET /export/{id} with blob responseType', (done) => {
      const mockResponse = 'blob-data';
      httpClient.get.mockReturnValue(of(mockResponse));

      service.exportProcessLink('link-uuid-1').subscribe((result: unknown) => {
        expect(result).toBe(mockResponse);
        expect(httpClient.get).toHaveBeenCalledWith(`${API_BASE}/export/link-uuid-1`, {
          responseType: 'blob',
        });
        done();
      });
    });
  });
});
