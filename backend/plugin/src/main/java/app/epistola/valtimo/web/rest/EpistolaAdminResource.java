package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.authorization.EpistolaAdministration;
import app.epistola.valtimo.authorization.EpistolaAdministrationActionProvider;
import app.epistola.valtimo.service.admin.EpistolaAdminService;
import app.epistola.valtimo.web.rest.dto.BpmnValidationViolation;
import app.epistola.valtimo.web.rest.dto.CatalogRedeployResult;
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
     * Get the latest BPMN race-safety validation violations across all deployed
     * process definitions. Empty list = healthy. The frontend uses this to show a
     * warning badge on the admin page when any deployed process definition violates
     * the rule that {@code generate-document} must flow synchronously into the
     * {@code EpistolaDocumentGenerated} catch event.
     */
    @GetMapping("/validations")
    public ResponseEntity<List<BpmnValidationViolation>> getValidationViolations() {
        requireManagePermission();
        return ResponseEntity.ok(adminService.getValidationViolations());
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
     * success, 502 (with the same body, carrying {@code errorMessage}) when the import
     * failed, or 400 when the configuration id or catalog slug is unknown.
     */
    @PostMapping("/configurations/{configurationId}/catalogs/{slug}/redeploy")
    public ResponseEntity<CatalogRedeployResult> redeployCatalog(
            @PathVariable String configurationId, @PathVariable String slug) {
        requireManagePermission();
        log.info("Manual catalog redeploy requested: configuration={}, slug={}", configurationId, slug);
        CatalogRedeployResult result = adminService.redeployCatalog(configurationId, slug);
        HttpStatus status = result.success() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(result);
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

    private void requireManagePermission() {
        authorizationService.requirePermission(
                new EntityAuthorizationRequest<>(
                        EpistolaAdministration.class,
                        EpistolaAdministrationActionProvider.MANAGE));
    }
}
