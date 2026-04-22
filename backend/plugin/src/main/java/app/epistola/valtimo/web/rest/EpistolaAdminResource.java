package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.service.EpistolaAdminService;
import app.epistola.valtimo.web.rest.dto.ConnectionStatus;
import app.epistola.valtimo.web.rest.dto.PluginUsageEntry;
import app.epistola.valtimo.web.rest.dto.VersionInfo;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
