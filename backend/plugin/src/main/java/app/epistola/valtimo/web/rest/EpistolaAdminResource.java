package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.service.EpistolaAdminService;
import app.epistola.valtimo.web.rest.dto.ConnectionStatus;
import app.epistola.valtimo.web.rest.dto.PendingJob;
import app.epistola.valtimo.web.rest.dto.PluginUsageEntry;
import app.epistola.valtimo.web.rest.dto.ProcessLinkExport;
import app.epistola.valtimo.web.rest.dto.VersionInfo;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Epistola plugin administrative operations.
 * Provides health checks, version info, and usage overview.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola/admin")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaAdminResource {

    private final EpistolaAdminService adminService;

    /**
     * Check connectivity to Epistola for all plugin configurations.
     */
    @GetMapping("/health")
    public ResponseEntity<List<ConnectionStatus>> checkConnections() {
        log.debug("Checking Epistola connection health");
        return ResponseEntity.ok(adminService.checkConnections());
    }

    /**
     * Get version information for the plugin and connected Epistola server.
     */
    @GetMapping("/versions")
    public ResponseEntity<VersionInfo> getVersions() {
        log.debug("Fetching Epistola version info");
        return ResponseEntity.ok(adminService.getVersions());
    }

    /**
     * Get an overview of all Epistola plugin usages across process definitions.
     */
    @GetMapping("/usage")
    public ResponseEntity<List<PluginUsageEntry>> getPluginUsage() {
        log.debug("Fetching Epistola plugin usage overview");
        return ResponseEntity.ok(adminService.getPluginUsage());
    }

    /**
     * Get all process instances currently waiting for an Epistola document generation result.
     */
    @GetMapping("/pending")
    public ResponseEntity<List<PendingJob>> getPendingJobs() {
        log.debug("Fetching pending Epistola jobs");
        return ResponseEntity.ok(adminService.getPendingJobs());
    }

    /**
     * Export a single process link in Valtimo's .process-link.json auto-deploy format.
     */
    @GetMapping("/export/{processLinkId}")
    public ResponseEntity<ProcessLinkExport> exportProcessLink(@PathVariable UUID processLinkId) {
        log.debug("Exporting process link {}", processLinkId);
        ProcessLinkExport export = adminService.exportProcessLink(processLinkId);
        String filename = export.activityId() + ".process-link.json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(export);
    }
}
