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
