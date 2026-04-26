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
