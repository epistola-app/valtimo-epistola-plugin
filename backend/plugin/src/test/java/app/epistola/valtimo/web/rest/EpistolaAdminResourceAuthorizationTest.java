package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.authorization.EpistolaAdministration;
import app.epistola.valtimo.authorization.EpistolaAdministrationActionProvider;
import app.epistola.valtimo.service.admin.EpistolaAdminService;
import com.ritense.authorization.AuthorizationService;
import com.ritense.authorization.request.AuthorizationRequest;
import com.ritense.authorization.request.EntityAuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EpistolaAdminResourceAuthorizationTest {

    private AuthorizationService authorizationService;
    private EpistolaAdminService adminService;
    private EpistolaAdminResource resource;

    @BeforeEach
    void setUp() {
        authorizationService = mock(AuthorizationService.class);
        adminService = mock(EpistolaAdminService.class);
        resource = new EpistolaAdminResource(adminService, authorizationService);
    }

    @Test
    void everyEndpoint_requiresEpistolaAdministrationManage() {
        when(adminService.checkConnections()).thenReturn(List.of());
        when(adminService.getPluginUsage()).thenReturn(List.of());
        when(adminService.getPendingJobs()).thenReturn(List.of());

        resource.checkConnections();
        resource.getVersions();
        resource.getPluginUsage();
        resource.getPendingJobs();

        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationService, times(4)).requirePermission(captor.capture());
        captor.getAllValues().forEach(request -> {
            var entityRequest = (EntityAuthorizationRequest<?>) request;
            assertThat(entityRequest.getResourceType()).isEqualTo(EpistolaAdministration.class);
            assertThat(entityRequest.getAction()).isEqualTo(EpistolaAdministrationActionProvider.MANAGE);
            assertThat(entityRequest.getEntities()).isEmpty();
        });
    }

    @Test
    void exportProcessLink_requiresEpistolaAdministrationManage() {
        var processLinkId = UUID.randomUUID();
        var export = new app.epistola.valtimo.web.rest.dto.ProcessLinkExport(
                "activity-1", "userTask", "PLUGIN", "config-1", "generate-document", null);
        when(adminService.exportProcessLink(processLinkId)).thenReturn(export);

        var response = resource.exportProcessLink(processLinkId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(authorizationService).requirePermission(any());
    }

    @Test
    void everyEndpoint_propagatesAccessDeniedAs403() {
        doThrow(new AccessDeniedException("nope"))
                .when(authorizationService).requirePermission(any());

        assertThatThrownBy(() -> resource.checkConnections()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resource.getVersions()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resource.getPluginUsage()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resource.getPendingJobs()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resource.exportProcessLink(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
