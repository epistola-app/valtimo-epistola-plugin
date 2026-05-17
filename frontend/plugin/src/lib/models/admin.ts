/**
 * Connection health check result for a single Epistola plugin configuration.
 */
export interface ConnectionStatus {
  configurationId: string;
  configurationTitle: string;
  tenantId: string;
  reachable: boolean;
  latencyMs: number;
  errorMessage?: string;
  serverVersion?: string;
}

/**
 * Version information for the Epistola plugin and connected server.
 */
export interface VersionInfo {
  pluginVersion: string;
  epistolaServerVersion?: string;
}

/**
 * Describes a single usage of an Epistola plugin action within a process definition.
 */
/**
 * A process instance currently waiting for an Epistola document generation result.
 */
export interface PendingJob {
  executionId: string;
  processInstanceId: string;
  processDefinitionKey: string;
  processDefinitionName: string;
  activityId: string;
  activityName: string;
  tenantId: string;
  requestId: string;
  configurationTitle: string;
}

export interface PluginUsageEntry {
  processLinkId: string;
  caseDefinitionKey?: string;
  caseDefinitionVersionTag?: string;
  processDefinitionKey: string;
  processDefinitionName: string;
  activityId: string;
  activityName: string;
  actionKey: string;
  configurationId: string;
  configurationTitle: string;
  problems: string[];
}

/**
 * Outcome of a manual reconcile attempt for a stuck Epistola catch event.
 * `correlated=true` means the Epistola job is in a terminal state and message
 * correlation ran (count = how many process instances received it). `false`
 * means the job is still PENDING/IN_PROGRESS — the backend returns 409.
 */
export interface ReconcileResult {
  executionId: string;
  processInstanceId: string;
  tenantId: string;
  requestId: string;
  epistolaStatus: string;
  correlatedCount: number | null;
  correlated: boolean;
}

/**
 * A catalog discovered on the application classpath, available to manually
 * redeploy to a plugin configuration's Epistola installation. `deployedVersion`
 * is tracked in memory per running backend instance (reset on restart), so
 * `upToDate` is a best-effort hint, not a guarantee.
 */
export interface ClasspathCatalog {
  slug: string;
  version: string;
  deployedVersion: string | null;
  upToDate: boolean;
}

/**
 * Outcome of a manual single-catalog redeploy. `success=false` carries the
 * reason in `errorMessage`; the backend returns HTTP 502 in that case.
 */
export interface CatalogRedeployResult {
  slug: string;
  version: string;
  success: boolean;
  catalogKey: string | null;
  installed: number;
  updated: number;
  failed: number;
  total: number;
  errorMessage: string | null;
}

/**
 * A BPMN race-safety violation reported by the backend's deployment validator.
 * `code` is one of the constants in
 * `app.epistola.valtimo.web.rest.dto.BpmnValidationViolation` (kept stable so
 * the UI can render specific remediation copy per code).
 */
export interface BpmnValidationViolation {
  processDefinitionKey: string;
  processDefinitionName: string;
  activityId: string;
  code: string;
  message: string;
}
