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
    private EpistolaFormCarrierRepairService formCarrierRepairService;
    private EpistolaLegacyOverrideScanService legacyOverrideScanService;
    private EpistolaAdminResource resource;

    @BeforeEach
    void setUp() {
        authorizationService = mock(AuthorizationService.class);
        adminService = mock(EpistolaAdminService.class);
        formCarrierRepairService = mock(EpistolaFormCarrierRepairService.class);
        legacyOverrideScanService = mock(EpistolaLegacyOverrideScanService.class);
        resource = new EpistolaAdminResource(
                adminService, authorizationService, formCarrierRepairService, legacyOverrideScanService);
    }

    @Test
    void legacyOverrideForms_requireEpistolaAdministrationManage() {
        when(legacyOverrideScanService.findLegacyForms()).thenReturn(List.of());

        resource.legacyOverrideForms();

        verify(authorizationService).requirePermission(any());
    }

    @Test
    void formCarrierEndpoints_requireEpistolaAdministrationManage() {
        when(formCarrierRepairService.findIssues()).thenReturn(List.of());
        when(formCarrierRepairService.repair(any())).thenReturn(
                new EpistolaFormCarrierRepairService.FormCarrierRepairResult("f", "n", true, 0, null));
        when(formCarrierRepairService.repairAll()).thenReturn(
                new EpistolaFormCarrierRepairService.FormCarrierRepairSummary(0, 0, 0));

        resource.formCarrierIssues();
        resource.repairFormCarrier(UUID.randomUUID());
        resource.repairAllFormCarriers();

        verify(authorizationService, times(3)).requirePermission(any());
    }

    @Test
    void repairFormCarrier_mapsResultToHttpStatus() {
        UUID id = UUID.randomUUID();

        when(formCarrierRepairService.repair(id)).thenReturn(
                new EpistolaFormCarrierRepairService.FormCarrierRepairResult(id.toString(), "ok", true, 1, null));
        assertThat(resource.repairFormCarrier(id).getStatusCode().value()).isEqualTo(200);

        when(formCarrierRepairService.repair(id)).thenReturn(
                new EpistolaFormCarrierRepairService.FormCarrierRepairResult(
                        id.toString(), null, false, 0, "Form not found"));
        assertThat(resource.repairFormCarrier(id).getStatusCode().value()).isEqualTo(404);

        when(formCarrierRepairService.repair(id)).thenReturn(
                new EpistolaFormCarrierRepairService.FormCarrierRepairResult(
                        id.toString(), "boom", false, 0, "disk on fire"));
        assertThat(resource.repairFormCarrier(id).getStatusCode().value()).isEqualTo(502);
    }

    @Test
    void redeployCatalog_mapsResultToHttpStatus() {
        // Success → 200.
        when(adminService.redeployCatalog("cfg", "demo")).thenReturn(
                new app.epistola.valtimo.web.rest.dto.CatalogRedeployResult(
                        "demo", "1.1", true, "demo", 8, 0, 0, 8, null, null));
        assertThat(resource.redeployCatalog("cfg", "demo").getStatusCode().value()).isEqualTo(200);

        // Downstream client-class (4xx) failure → 422 (operator must ship a fixed catalog).
        when(adminService.redeployCatalog("cfg", "demo")).thenReturn(
                new app.epistola.valtimo.web.rest.dto.CatalogRedeployResult(
                        "demo", "1.1", false, null, 0, 0, 0, 0, "wire schema too old", 400));
        assertThat(resource.redeployCatalog("cfg", "demo").getStatusCode().value()).isEqualTo(422);

        // Downstream 5xx → 502 (retry might help).
        when(adminService.redeployCatalog("cfg", "demo")).thenReturn(
                new app.epistola.valtimo.web.rest.dto.CatalogRedeployResult(
                        "demo", "1.1", false, null, 0, 0, 0, 0, "suite unavailable", 503));
        assertThat(resource.redeployCatalog("cfg", "demo").getStatusCode().value()).isEqualTo(502);

        // No downstream status (build/connectivity failure) → 502.
        when(adminService.redeployCatalog("cfg", "demo")).thenReturn(
                new app.epistola.valtimo.web.rest.dto.CatalogRedeployResult(
                        "demo", "1.1", false, null, 0, 0, 0, 0, "connection refused", null));
        assertThat(resource.redeployCatalog("cfg", "demo").getStatusCode().value()).isEqualTo(502);
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
                "activity-1", "userTask", "PLUGIN", "config-1", "epistola-generate-document", null);
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
