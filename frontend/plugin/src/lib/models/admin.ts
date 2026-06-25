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
  /**
   * 'WAITING' — has the epistolaWaitFor token; the collector can correlate it.
   * 'UNWIRED' — has the subscription but no token, so it can never be correlated (stuck); requestId is
   * absent and reconcile cannot recover it. Surfaced so operators can see and fix the process model.
   */
  status: 'WAITING' | 'UNWIRED';
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
 * A catalog discovered on the application classpath, with whether it currently
 * exists in the connected Epistola installation. `status` is resolved live by
 * querying Epistola at request time:
 * - `IN_EPISTOLA` — a catalog with this slug exists in Epistola.
 * - `NOT_IN_EPISTOLA` — Epistola was reached and has no such catalog (redeploy it).
 * - `UNKNOWN` — Epistola could not be reached, so existence is undetermined.
 */
export interface ClasspathCatalog {
  slug: string;
  version: string;
  status: 'IN_EPISTOLA' | 'NOT_IN_EPISTOLA' | 'UNKNOWN';
}

/**
 * Outcome of a manual single-catalog redeploy. `success=false` carries the
 * reason in `errorMessage`; the backend returns 422 for a downstream client-class
 * (4xx) failure, e.g. a too-old catalog wire schema, and 502 for a 5xx /
 * connectivity failure. `httpStatus` is the downstream Epistola status, when known.
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
  httpStatus?: number | null;
}

/**
 * A "Keep a Changelog" section within a release (e.g. "Added") and its items.
 */
export interface ChangelogSection {
  title: string;
  items: string[];
}

/**
 * One release block from the bundled CHANGELOG, parsed server-side (newest
 * first). `date` is null for the Unreleased block.
 */
export interface ChangelogRelease {
  version: string;
  date: string | null;
  sections: ChangelogSection[];
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

/**
 * The BPMN race-safety validation report from `GET /admin/validations`.
 * `lastCheckedAt` is an ISO-8601 timestamp of the last completed scan, or `null`
 * if no scan has run yet. `refreshIntervalMs` is the validator's scan cadence, used
 * to tell the operator how often the result refreshes. Only the latest deployed
 * version of each process definition is inspected.
 */
export interface BpmnValidationReport {
  lastCheckedAt: string | null;
  refreshIntervalMs: number;
  violations: BpmnValidationViolation[];
}

/**
 * TEMPORARY (removed in 1.0.0). A form whose Epistola components are missing the
 * task-id carrier field — surfaced on the admin page so it can be repaired.
 * `readOnly` flags classpath-deployed forms (a repair there is reverted on the next
 * boot; fix the source instead).
 */
export interface FormCarrierIssue {
  formId: string;
  name: string;
  missingComponents: number;
  readOnly: boolean;
}

/**
 * TEMPORARY. A form whose `epistola-document-preview` components still use the legacy
 * override-mapping object format (`{ scope: { path: "form:key" } }`) instead of the new
 * JSONata string over `$form`. Surfaced on the admin page so it can be re-saved in the
 * form builder, which migrates it. `readOnly` flags classpath-deployed forms (migrate
 * the source instead).
 */
export interface LegacyOverrideForm {
  formId: string;
  name: string;
  legacyComponents: number;
  readOnly: boolean;
}

/** TEMPORARY (removed in 1.0.0). Outcome of repairing one form's carrier. */
export interface FormCarrierRepairResult {
  formId: string;
  name: string | null;
  success: boolean;
  componentsPatched: number;
  errorMessage: string | null;
}

/** TEMPORARY (removed in 1.0.0). Aggregate outcome of repairing all flagged forms. */
export interface FormCarrierRepairSummary {
  formsRepaired: number;
  componentsPatched: number;
  failed: number;
}
