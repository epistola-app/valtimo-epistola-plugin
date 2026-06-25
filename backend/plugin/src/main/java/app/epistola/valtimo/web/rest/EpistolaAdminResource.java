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
package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.authorization.EpistolaAdministration;
import app.epistola.valtimo.authorization.EpistolaAdministrationActionProvider;
import app.epistola.valtimo.service.admin.EpistolaAdminService;
import app.epistola.valtimo.service.admin.EpistolaFormCarrierRepairService;
import app.epistola.valtimo.service.admin.EpistolaLegacyOverrideScanService;
import app.epistola.valtimo.web.rest.dto.BpmnValidationReport;
import app.epistola.valtimo.web.rest.dto.CatalogRedeployResult;
import app.epistola.valtimo.web.rest.dto.ChangelogRelease;
import app.epistola.valtimo.web.rest.dto.ClasspathCatalog;
import app.epistola.valtimo.web.rest.dto.ConnectionStatus;
import app.epistola.valtimo.web.rest.dto.PendingJob;
import app.epistola.valtimo.web.rest.dto.PluginUsageEntry;
import app.epistola.valtimo.web.rest.dto.ProcessLinkExport;
import app.epistola.valtimo.web.rest.dto.ReconcileResult;
import app.epistola.valtimo.web.rest.dto.VersionInfo;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Epistola plugin administrative operations.
 *
 * <p>Every endpoint requires {@code EpistolaAdministration:MANAGE}. By default that
 * permission is granted to {@code ROLE_ADMIN} via the seeded
 * {@code epistola-admin-default.permission.json} changeset; operators who want a stricter
 * setup can revoke it from {@code ROLE_ADMIN} and assign it to a more specific role.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola/admin")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaAdminResource {

    private final EpistolaAdminService adminService;
    private final AuthorizationService authorizationService;
    // TEMPORARY (remove in 1.0.0): detect/repair forms missing the task-id carrier.
    private final EpistolaFormCarrierRepairService formCarrierRepairService;
    // TEMPORARY: detect forms still using the legacy override-mapping object format.
    private final EpistolaLegacyOverrideScanService legacyOverrideScanService;

    /**
     * Check connectivity to Epistola for all plugin configurations.
     */
    @GetMapping("/health")
    public ResponseEntity<List<ConnectionStatus>> checkConnections() {
        requireManagePermission();
        log.debug("Checking Epistola connection health");
        return ResponseEntity.ok(adminService.checkConnections());
    }

    /**
     * Get version information for the plugin and connected Epistola server.
     */
    @GetMapping("/versions")
    public ResponseEntity<VersionInfo> getVersions() {
        requireManagePermission();
        log.debug("Fetching Epistola version info");
        return ResponseEntity.ok(adminService.getVersions());
    }

    /**
     * Get the plugin CHANGELOG, parsed from the markdown bundled into the running
     * jar into structured releases so the admin page can render it without a
     * markdown renderer.
     */
    @GetMapping("/changelog")
    public ResponseEntity<List<ChangelogRelease>> getChangelog() {
        requireManagePermission();
        log.debug("Fetching plugin changelog");
        return ResponseEntity.ok(adminService.getChangelog());
    }

    /**
     * Get an overview of all Epistola plugin usages across process definitions.
     */
    @GetMapping("/usage")
    public ResponseEntity<List<PluginUsageEntry>> getPluginUsage() {
        requireManagePermission();
        log.debug("Fetching Epistola plugin usage overview");
        return ResponseEntity.ok(adminService.getPluginUsage());
    }

    /**
     * Get all process instances currently waiting for an Epistola document generation result.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<PendingJob>> getPendingJobs() {
        requireManagePermission();
        log.debug("Fetching pending Epistola jobs");
        return ResponseEntity.ok(adminService.getPendingJobs());
    }

    /**
     * Get the latest BPMN race-safety validation report across all deployed process
     * definitions: the violation snapshot (empty = healthy) plus when it was last
     * checked and how often it refreshes. The frontend uses this to show a warning
     * badge when any deployed process definition violates the rule that
     * {@code generate-document} must flow synchronously into the
     * {@code EpistolaDocumentGenerated} catch event, and to convey result freshness.
     * Only the latest deployed version of each process definition is inspected.
     */
    @GetMapping("/validations")
    public ResponseEntity<BpmnValidationReport> getValidationReport() {
        requireManagePermission();
        return ResponseEntity.ok(adminService.getValidationReport());
    }

    /**
     * Manually reconcile a stuck Epistola catch event. The Pending Jobs admin UI
     * surfaces a button per row that calls this endpoint when a process instance
     * has been waiting on {@code EpistolaDocumentGenerated} longer than expected.
     *
     * <p>Returns 200 if the Epistola job is in a terminal state (COMPLETED / FAILED /
     * CANCELLED) and message correlation ran (regardless of how many executions it
     * matched). Returns 409 if the job is still PENDING / IN_PROGRESS — the UI should
     * surface "still in progress, try again later" rather than treating that as an
     * error. Returns 400 / 404-equivalent (mapped from {@link IllegalArgumentException}
     * by Valtimo's global advice) when the execution doesn't exist or isn't waiting
     * for an Epistola message.
     */
    @PostMapping("/pending/{executionId}/reconcile")
    public ResponseEntity<ReconcileResult> reconcilePending(@PathVariable String executionId) {
        requireManagePermission();
        log.info("Manual reconcile requested for execution {}", executionId);
        ReconcileResult result = adminService.reconcile(executionId);
        HttpStatus status = result.correlated() ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * List the classpath catalogs available to manually redeploy for a plugin
     * configuration, each annotated with the version last deployed in this process.
     * Returns 400 (via Valtimo's global advice) when the configuration id is unknown.
     */
    @GetMapping("/configurations/{configurationId}/catalogs")
    public ResponseEntity<List<ClasspathCatalog>> listClasspathCatalogs(
            @PathVariable String configurationId) {
        requireManagePermission();
        log.debug("Listing classpath catalogs for configuration {}", configurationId);
        return ResponseEntity.ok(adminService.listClasspathCatalogs(configurationId));
    }

    /**
     * Force-redeploy a single classpath catalog to the configuration's Epistola
     * installation. Explicit operator action — bypasses the {@code templateSyncEnabled}
     * gate and the version-skip check. Returns 200 with the per-resource counts on
     * success; on failure the same body (carrying {@code errorMessage}) is returned with
     * a status that reflects the cause — a downstream 4xx becomes {@code 422 Unprocessable
     * Entity} (a deterministic data problem, e.g. a too-old catalog wire schema), while a
     * 5xx / connectivity failure stays {@code 502 Bad Gateway}. A 400 is returned when the
     * configuration id or catalog slug is unknown.
     */
    @PostMapping("/configurations/{configurationId}/catalogs/{slug}/redeploy")
    public ResponseEntity<CatalogRedeployResult> redeployCatalog(
            @PathVariable String configurationId, @PathVariable String slug) {
        requireManagePermission();
        log.info("Manual catalog redeploy requested: configuration={}, slug={}", configurationId, slug);
        CatalogRedeployResult result = adminService.redeployCatalog(configurationId, slug);
        return ResponseEntity.status(redeployStatus(result)).body(result);
    }

    /**
     * Map a redeploy outcome to an HTTP status: success → 200; a downstream client-class
     * (4xx) failure → 422 (the operator must ship a fixed catalog, not retry); everything
     * else (5xx, connectivity, build failure) → 502.
     */
    private static HttpStatus redeployStatus(CatalogRedeployResult result) {
        if (result.success()) {
            return HttpStatus.OK;
        }
        Integer downstream = result.httpStatus();
        if (downstream != null && downstream >= 400 && downstream < 500) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    /**
     * Export a single process link in Valtimo's .process-link.json auto-deploy format.
     */
    @GetMapping("/export/{processLinkId}")
    public ResponseEntity<ProcessLinkExport> exportProcessLink(@PathVariable UUID processLinkId) {
        requireManagePermission();
        log.debug("Exporting process link {}", processLinkId);
        ProcessLinkExport export = adminService.exportProcessLink(processLinkId);
        String filename = export.activityId() + ".process-link.json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(export);
    }

    // ---- TEMPORARY (remove in 1.0.0): task-id carrier detection + repair ----

    /**
     * Lists forms whose Epistola components are missing the task-id carrier field.
     */
    @GetMapping("/forms/carrier-issues")
    public ResponseEntity<List<EpistolaFormCarrierRepairService.FormCarrierIssue>> formCarrierIssues() {
        requireManagePermission();
        return ResponseEntity.ok(formCarrierRepairService.findIssues());
    }

    /**
     * Injects the task-id carrier into a single form's Epistola components. 200 on success,
     * 404 when the form does not exist, 502 when the repair fails.
     */
    @PostMapping("/forms/{formId}/repair-carrier")
    public ResponseEntity<EpistolaFormCarrierRepairService.FormCarrierRepairResult> repairFormCarrier(
            @PathVariable UUID formId) {
        requireManagePermission();
        log.info("Manual form carrier repair requested: form={}", formId);
        EpistolaFormCarrierRepairService.FormCarrierRepairResult result = formCarrierRepairService.repair(formId);
        if (result.success()) {
            return ResponseEntity.ok(result);
        }
        HttpStatus status = "Form not found".equals(result.errorMessage())
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Repairs every form returned by {@link #formCarrierIssues()}.
     */
    @PostMapping("/forms/repair-carrier")
    public ResponseEntity<EpistolaFormCarrierRepairService.FormCarrierRepairSummary> repairAllFormCarriers() {
        requireManagePermission();
        log.info("Manual form carrier repair-all requested");
        return ResponseEntity.ok(formCarrierRepairService.repairAll());
    }

    /**
     * Lists forms whose {@code epistola-document-preview} components still use the legacy
     * override-mapping object format (re-save the form in the builder to migrate it).
     */
    @GetMapping("/forms/legacy-override")
    public ResponseEntity<List<EpistolaLegacyOverrideScanService.LegacyOverrideForm>> legacyOverrideForms() {
        requireManagePermission();
        return ResponseEntity.ok(legacyOverrideScanService.findLegacyForms());
    }

    private void requireManagePermission() {
        authorizationService.requirePermission(
                new EntityAuthorizationRequest<>(
                        EpistolaAdministration.class,
                        EpistolaAdministrationActionProvider.MANAGE));
    }
}
