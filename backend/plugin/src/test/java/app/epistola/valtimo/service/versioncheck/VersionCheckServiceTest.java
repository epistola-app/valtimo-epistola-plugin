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
package app.epistola.valtimo.service.versioncheck;

import app.epistola.valtimo.config.EpistolaProperties;
import app.epistola.valtimo.web.rest.dto.VersionCheckStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VersionCheckServiceTest {

    @Test
    void disabledStatusDoesNotFetchMetadata() {
        EpistolaProperties properties = new EpistolaProperties();
        properties.getVersionCheck().setEnabled(false);
        VersionCheckClient client = mock(VersionCheckClient.class);

        VersionCheckStatus status = new VersionCheckService(
                client,
                mock(VersionCheckIdentityProvider.class),
                properties,
                mock(TaskScheduler.class)
        ).status("1.0.0");

        assertThat(status.enabled()).isFalse();
        assertThat(status.currentVersion()).isEqualTo("1.0.0");
        verifyNoInteractions(client);
    }

    @Test
    void statusFetchesAndCachesReleaseMetadata() {
        EpistolaProperties properties = new EpistolaProperties();
        VersionCheckClient client = mock(VersionCheckClient.class);
        VersionCheckIdentityProvider identityProvider = mock(VersionCheckIdentityProvider.class);
        when(identityProvider.valtimoInstallationId()).thenReturn("valtimo-1");
        when(client.fetch(
                properties.getVersionCheck().getWellKnownUrl(),
                "1.0.0",
                "valtimo-1"
        )).thenReturn(new EpistolaReleasesDocument(1, Map.of(
                "valtimo-epistola-plugin",
                new EpistolaReleasesDocument.ProductReleases(
                        new EpistolaReleasesDocument.ReleaseRef("1.1.0", null, null, null),
                        null,
                        null
                )
        )));

        VersionCheckService service = new VersionCheckService(
                client, identityProvider, properties, mock(TaskScheduler.class));

        VersionCheckStatus first = service.status("1.0.0");
        VersionCheckStatus second = service.status("1.0.0");

        assertThat(first.updateAvailable()).isTrue();
        assertThat(second).isSameAs(first);
        verify(client).fetch(properties.getVersionCheck().getWellKnownUrl(), "1.0.0", "valtimo-1");
    }

    @Test
    void scheduledCheckDoesNotScheduleAnythingWhenDisabled() {
        EpistolaProperties properties = new EpistolaProperties();
        properties.getVersionCheck().setEnabled(false);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        VersionCheckService service = new VersionCheckService(
                mock(VersionCheckClient.class),
                mock(VersionCheckIdentityProvider.class),
                properties,
                taskScheduler
        );

        service.scheduledCheck();

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void nextJitterMsIsWithinTheConfiguredWindow() {
        EpistolaProperties properties = new EpistolaProperties();
        properties.getVersionCheck().setMaxJitter(Duration.ofHours(1));
        VersionCheckService service = new VersionCheckService(
                mock(VersionCheckClient.class),
                mock(VersionCheckIdentityProvider.class),
                properties,
                mock(TaskScheduler.class)
        );

        for (int i = 0; i < 1000; i++) {
            assertThat(service.nextJitterMs()).isBetween(0L, 3_600_000L);
        }
    }

    @Test
    void scheduledCheckDefersTheFetchWithJitter() {
        EpistolaProperties properties = new EpistolaProperties();
        VersionCheckClient client = mock(VersionCheckClient.class);
        VersionCheckIdentityProvider identityProvider = mock(VersionCheckIdentityProvider.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        when(identityProvider.valtimoInstallationId()).thenReturn("valtimo-1");
        when(client.fetch(
                properties.getVersionCheck().getWellKnownUrl(),
                "development",
                "valtimo-1"
        )).thenReturn(new EpistolaReleasesDocument(1, Map.of(
                "valtimo-epistola-plugin",
                new EpistolaReleasesDocument.ProductReleases(
                        new EpistolaReleasesDocument.ReleaseRef("1.1.0", null, null, null),
                        null,
                        null
                )
        )));
        VersionCheckService service = new VersionCheckService(client, identityProvider, properties, taskScheduler);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> runAt = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        service.scheduledCheck();

        verify(taskScheduler).schedule(task.capture(), runAt.capture());
        long delayMs = Duration.between(before, runAt.getValue()).toMillis();
        assertThat(delayMs).isBetween(0L, 3_601_000L);

        task.getValue().run();

        verify(client).fetch(properties.getVersionCheck().getWellKnownUrl(), "development", "valtimo-1");
    }
}
