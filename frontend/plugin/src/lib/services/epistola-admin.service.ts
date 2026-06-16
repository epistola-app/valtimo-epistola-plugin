import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@valtimo/shared';
import { Observable } from 'rxjs';
import {
  BpmnValidationReport,
  CatalogRedeployResult,
  ChangelogRelease,
  ClasspathCatalog,
  ConnectionStatus,
  FormCarrierIssue,
  FormCarrierRepairResult,
  FormCarrierRepairSummary,
  PendingJob,
  PluginUsageEntry,
  ReconcileResult,
  VersionInfo,
} from '../models';

/**
 * Service for Epistola plugin administrative operations.
 * Provides health checks, version info, and usage overview.
 */
@Injectable()
export class EpistolaAdminService {
  private readonly apiEndpoint: string;

  constructor(
    private readonly http: HttpClient,
    private readonly configService: ConfigService,
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

  /**
   * Get all process instances currently waiting for an Epistola document generation result.
   */
  getPendingJobs(): Observable<PendingJob[]> {
    return this.http.get<PendingJob[]>(`${this.apiEndpoint}/pending`);
  }

  /**
   * Manually reconcile a stuck Epistola catch event by querying Epistola for the
   * job's current status and re-running message correlation. Returns 200 with
   * `correlated=true` on success, 409 with `correlated=false` when the job is
   * still in flight, or surfaces a validation error when the execution can't be
   * recovered (no subscription, missing variable, unknown tenant).
   */
  reconcilePending(executionId: string): Observable<ReconcileResult> {
    return this.http.post<ReconcileResult>(
      `${this.apiEndpoint}/pending/${encodeURIComponent(executionId)}/reconcile`,
      null,
    );
  }

  /**
   * Get the latest BPMN race-safety validation report across deployed process
   * definitions: the violations (empty = healthy) plus when it was last checked
   * and how often it refreshes.
   */
  getValidationReport(): Observable<BpmnValidationReport> {
    return this.http.get<BpmnValidationReport>(`${this.apiEndpoint}/validations`);
  }

  /**
   * List the classpath catalogs available to manually redeploy for a plugin
   * configuration, each annotated with the version last deployed in this
   * backend process.
   */
  getClasspathCatalogs(configurationId: string): Observable<ClasspathCatalog[]> {
    return this.http.get<ClasspathCatalog[]>(
      `${this.apiEndpoint}/configurations/${encodeURIComponent(configurationId)}/catalogs`,
    );
  }

  /**
   * Force-redeploy a single classpath catalog to the configuration's Epistola
   * installation. Explicit operator action — bypasses the templateSyncEnabled
   * gate and the version-skip check. Returns 200 with per-resource counts on
   * success, or 502 (error callback, body carries `errorMessage`) on failure.
   */
  redeployCatalog(configurationId: string, slug: string): Observable<CatalogRedeployResult> {
    return this.http.post<CatalogRedeployResult>(
      `${this.apiEndpoint}/configurations/${encodeURIComponent(configurationId)}/catalogs/${encodeURIComponent(slug)}/redeploy`,
      null,
    );
  }

  /**
   * Get the plugin CHANGELOG parsed (server-side) into structured releases for
   * the admin Changelog tab — no markdown renderer needed on the client.
   */
  getChangelog(): Observable<ChangelogRelease[]> {
    return this.http.get<ChangelogRelease[]>(`${this.apiEndpoint}/changelog`);
  }

  /**
   * Export a single process link as a .process-link.json file.
   */
  exportProcessLink(processLinkId: string): Observable<Blob> {
    return this.http.get(`${this.apiEndpoint}/export/${encodeURIComponent(processLinkId)}`, {
      responseType: 'blob',
    });
  }

  // ---- TEMPORARY (removed in 1.0.0): task-id carrier detection + repair ----

  /** Forms whose Epistola components are missing the task-id carrier. */
  getFormCarrierIssues(): Observable<FormCarrierIssue[]> {
    return this.http.get<FormCarrierIssue[]>(`${this.apiEndpoint}/forms/carrier-issues`);
  }

  /** Inject the task-id carrier into a single form's Epistola components. */
  repairFormCarrier(formId: string): Observable<FormCarrierRepairResult> {
    return this.http.post<FormCarrierRepairResult>(
      `${this.apiEndpoint}/forms/${encodeURIComponent(formId)}/repair-carrier`,
      null,
    );
  }

  /** Repair every flagged form. */
  repairAllFormCarriers(): Observable<FormCarrierRepairSummary> {
    return this.http.post<FormCarrierRepairSummary>(
      `${this.apiEndpoint}/forms/repair-carrier`,
      null,
    );
  }
}
