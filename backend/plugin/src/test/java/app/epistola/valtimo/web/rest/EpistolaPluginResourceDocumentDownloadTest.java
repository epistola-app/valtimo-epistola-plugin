package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.ProcessVariableDiscoveryService;
import com.ritense.plugin.domain.PluginConfiguration;
import com.ritense.plugin.service.PluginService;
import com.ritense.valtimo.epistola.plugin.EpistolaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EpistolaPluginResourceDocumentDownloadTest {

    private PluginService pluginService;
    private EpistolaService epistolaService;
    private EpistolaPluginResource resource;

    @BeforeEach
    void setUp() {
        pluginService = mock(PluginService.class);
        epistolaService = mock(EpistolaService.class);
        var processVariableDiscoveryService = mock(ProcessVariableDiscoveryService.class);
        resource = new EpistolaPluginResource(pluginService, epistolaService, processVariableDiscoveryService);
    }

    @Test
    void downloadDocument_shouldReturnPdfWithCorrectHeaders() {
        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        byte[] pdfContent = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF
        when(epistolaService.downloadDocument("https://api.epistola.app", "api-key", "tenant-a", "doc-123"))
                .thenReturn(pdfContent);

        ResponseEntity<byte[]> response = resource.downloadDocument("doc-123", "tenant-a", "bevestigingsbrief.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(pdfContent);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentDisposition())
                .isEqualTo(ContentDisposition.attachment().filename("bevestigingsbrief.pdf").build());
    }

    @Test
    void downloadDocument_shouldReturn404WhenNoMatchingTenant() {
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<byte[]> response = resource.downloadDocument("doc-123", "unknown-tenant", "document.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void downloadDocument_shouldReturn404WhenTenantDoesNotMatch() {
        EpistolaPlugin plugin = mockPlugin("https://api.epistola.app", "api-key", "tenant-a");
        PluginConfiguration config = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(config));
        when(pluginService.createInstance(config)).thenReturn(plugin);

        ResponseEntity<byte[]> response = resource.downloadDocument("doc-123", "tenant-b", "document.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void downloadDocument_shouldSelectCorrectPluginWhenMultipleExist() {
        EpistolaPlugin pluginA = mockPlugin("https://a.epistola.app", "key-a", "tenant-a");
        EpistolaPlugin pluginB = mockPlugin("https://b.epistola.app", "key-b", "tenant-b");
        PluginConfiguration configA = mock(PluginConfiguration.class);
        PluginConfiguration configB = mock(PluginConfiguration.class);
        when(pluginService.findPluginConfigurations(eq(EpistolaPlugin.class), any()))
                .thenReturn(List.of(configA, configB));
        when(pluginService.createInstance(configA)).thenReturn(pluginA);
        when(pluginService.createInstance(configB)).thenReturn(pluginB);

        byte[] pdfContent = new byte[]{0x25, 0x50, 0x44, 0x46};
        when(epistolaService.downloadDocument("https://b.epistola.app", "key-b", "tenant-b", "doc-456"))
                .thenReturn(pdfContent);

        ResponseEntity<byte[]> response = resource.downloadDocument("doc-456", "tenant-b", "output.pdf");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(pdfContent);
    }

    private EpistolaPlugin mockPlugin(String baseUrl, String apiKey, String tenantId) {
        EpistolaPlugin plugin = mock(EpistolaPlugin.class);
        when(plugin.getBaseUrl()).thenReturn(baseUrl);
        when(plugin.getApiKey()).thenReturn(apiKey);
        when(plugin.getTenantId()).thenReturn(tenantId);
        return plugin;
    }
}
